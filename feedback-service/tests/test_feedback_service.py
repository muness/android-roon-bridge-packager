import copy
import hashlib
import http.client
import importlib.util
import io
import json
from pathlib import Path
import socket
import tempfile
import threading
import time
import unittest
import uuid
from contextlib import redirect_stderr

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("feedback_service", ROOT / "feedback_service.py")
service = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(service)


def fixture(name):
    return json.loads((ROOT / "fixtures" / name).read_text())


class EvidenceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.store = service.Store(Path(self.temp.name) / "evidence.sqlite3")
        self.report = fixture("report.json")
        self.recipe = fixture("recipe.json")

    def tearDown(self):
        self.temp.cleanup()

    def decision(self, receipt, recipe=None, acknowledge=False):
        return {"reviewer": "maintainer", "reason": "Reviewed actual stream and hearing evidence.", "recipe": recipe or self.recipe, "evidence": [{"receiptId": receipt["receiptId"], "attemptId": self.report["attempts"][0]["id"]}], "acknowledgeConflicts": acknowledge}

    def test_retry_identity_uses_canonical_digest(self):
        first = self.store.receive(self.report)
        # Object ordering and whitespace do not produce another report/receipt.
        reordered = json.loads(json.dumps(self.report, sort_keys=True, indent=4))
        self.assertEqual(first, self.store.receive(reordered))
        self.assertEqual(1, len(self.store.reports()))
        reordered["attempts"][0]["hearing"] = "silence"
        with self.assertRaises(service.Conflict):
            self.store.receive(reordered)
        self.assertEqual("working", service.parse_json(self.store.reports()[0]["payload"])["attempts"][0]["hearing"])

    def test_distinct_positive_negative_attempts_survive(self):
        failed = copy.deepcopy(self.report["attempts"][0])
        failed.update(id=str(uuid.uuid4()), order=1, hearing="silence")
        failed["settings"]["force_encoding"] = "24"
        self.report["attempts"].append(failed)
        self.store.receive(self.report)
        stored = service.parse_json(self.store.reports()[0]["payload"])["attempts"]
        self.assertEqual(["working", "silence"], [a["hearing"] for a in stored])
        self.assertNotEqual(stored[0]["settings"], stored[1]["settings"])

    def test_typed_allowlist_rejects_personal_and_unknown_fields(self):
        for target, key, value in [
            ("report", "trackName", "Song title"),
            ("attempt", "usbSerial", "123456"),
            ("attempt", "endpointName", "Living room"),
            ("attempt", "log", "https://private.example/secret"),
            ("attempt", "ssid", "Home WiFi"),
            ("attempt", "model", "https://private.example/song"),
            ("attempt", "firmware", "account@example.com"),
            ("settings", "endpoint_name", "Private name"),
            ("settings", "log_transfers", "on"),
            ("settings", "dsd_native_format", "4294967296"),
            ("settings", "dsd_native_format", "0X1A000001"),
            ("settings", "direct_guard", "unbounded"),
            ("requested", "sampleFormat", "raw arbitrary diagnostic"),
        ]:
            with self.subTest(key=key):
                value_report = copy.deepcopy(self.report)
                obj = value_report if target == "report" else value_report["attempts"][0]
                if target in {"settings", "requested"}:
                    obj = obj[target]
                obj[key] = value
                with self.assertRaises(service.Invalid):
                    self.store.receive(value_report)
        self.assertEqual([], self.store.reports())

    def test_primitive_types_and_bounds(self):
        for key, value in [("sdk", True), ("sdk", 24), ("order", "0"), ("elapsedMs", -1), ("grant", False)]:
            report = copy.deepcopy(self.report)
            report["attempts"][0][key] = value
            with self.subTest(key=key, value=value), self.assertRaises(service.Invalid):
                self.store.receive(report)
        report = copy.deepcopy(self.report)
        report["attempts"] *= 33
        with self.assertRaises(service.Invalid):
            self.store.receive(report)
        report = copy.deepcopy(self.report)
        report["attempts"].append(copy.deepcopy(report["attempts"][0]))
        with self.assertRaises(service.Invalid):
            self.store.receive(report)
        with self.assertRaises(service.Invalid):
            service.parse_json(b'{"schemaVersion":1,"schemaVersion":1}')
        with self.assertRaises(service.Invalid):
            service.parse_json(b'{"elapsedMs":NaN}')

    def test_grants_do_not_become_hearing_and_faults_remain_distinct(self):
        attempt = self.report["attempts"][0]
        attempt["hearing"] = "unanswered"
        self.store.receive(self.report)
        self.assertEqual("unanswered", service.parse_json(self.store.reports()[0]["payload"])["attempts"][0]["hearing"])
        with self.assertRaisesRegex(service.Invalid, "heard_stream"):
            self.store.review(self.decision(self.store.receive(self.report)))

    def test_review_does_not_publish_automatically(self):
        receipt = self.store.receive(self.report)
        self.store.review(self.decision(receipt))
        self.assertIsNone(self.store.manifest())
        manifest = self.store.publish()
        catalog = service.parse_json(self.store.snapshot(manifest["sequence"]))
        self.assertEqual(self.recipe, catalog["recipes"][0])
        self.assertEqual(hashlib.sha256(self.store.snapshot(1)).hexdigest(), manifest["sha256"])

    def test_scope_and_engine_mismatch_rejected(self):
        receipt = self.store.receive(self.report)
        for key, value in [("model", "Player Two"), ("firmware", "release-2"), ("engineVersion", "another-engine"), ("endpoint", "naa"), ("settings", {"force_rate": "44100"})]:
            recipe = copy.deepcopy(self.recipe)
            recipe[key] = value
            with self.subTest(key=key), self.assertRaisesRegex(service.Invalid, "scope_or_settings"):
                self.store.review(self.decision(receipt, recipe))
        recipe = copy.deepcopy(self.recipe)
        recipe["model"] = "*"
        with self.assertRaisesRegex(service.Invalid, "exact_cohort"):
            self.store.review(self.decision(receipt, recipe))

    def test_catalog_unknown_fields_types_and_settings_rejected(self):
        catalog = {"schema": 1, "resolver": 1, "sequence": 1, "requiredFeatures": [], "recipes": [self.recipe]}
        self.assertEqual(catalog, service.validate_catalog(catalog))
        for key, value in [("url", "https://untrusted.example"), ("sequence", True), ("requiredFeatures", ["unknown-feature"]), ("recipes", "not-an-array")]:
            invalid = copy.deepcopy(catalog)
            invalid[key] = value
            with self.subTest(key=key), self.assertRaises(service.Invalid):
                service.validate_catalog(invalid)
        invalid = copy.deepcopy(catalog)
        invalid["recipes"][0]["settings"]["test_mode"] = "on"
        with self.assertRaises(service.Invalid):
            service.validate_catalog(invalid)

    def test_test_mode_and_assumed_usb_speed_cannot_qualify_recipe(self):
        for key in ("test_mode", "assume_high_speed"):
            report = copy.deepcopy(self.report)
            report["reportId"] = str(uuid.uuid4())
            report["attempts"][0]["settings"][key] = "on"
            receipt = self.store.receive(report)
            with self.subTest(key=key), self.assertRaisesRegex(service.Invalid, "heard_stream"):
                self.store.review(self.decision(receipt))

    def test_pcm_hearing_cannot_qualify_native_dsd(self):
        recipe = copy.deepcopy(self.recipe)
        recipe["requiredFeatures"] = ["dsd-reviewed-v1"]
        recipe["dsdMaxMultiple"] = 64
        recipe["settings"].update(dsd_port="vendor", dsd_native_format="13", dsd_packing="u32be")
        self.report["attempts"][0]["settings"].update(recipe["settings"])
        receipt = self.store.receive(self.report)
        with self.assertRaisesRegex(service.Invalid, "heard_stream"):
            self.store.review(self.decision(receipt, recipe))

    def test_native_dsd_requires_bounded_explicit_route_and_received_heard_format(self):
        recipe = copy.deepcopy(self.recipe)
        recipe["requiredFeatures"] = ["dsd-reviewed-v1"]
        recipe["dsdMaxMultiple"] = 128
        recipe["settings"].update(dsd_port="vendor", dsd_native_format="13", dsd_packing="u32be")
        self.report["attempts"][0]["settings"].update(recipe["settings"])
        stream = {"encoding": "dsd", "rate": 88200, "channels": 2, "sampleFormat": "DSD_U32_BE"}
        self.report["attempts"][0].update(requested=stream, actual=stream)
        receipt = self.store.receive(self.report)
        # 88200 ALSA words * 32 bits / 44100 = DSD64, so 128 is unproven.
        with self.assertRaisesRegex(service.Invalid, "heard_stream"):
            self.store.review(self.decision(receipt, recipe))
        recipe["dsdMaxMultiple"] = 64
        self.store.review(self.decision(receipt, recipe))
        manifest = self.store.publish()
        self.assertEqual(64, service.parse_json(self.store.snapshot(manifest["sequence"]))["recipes"][0]["dsdMaxMultiple"])
        for key in ("dsdMaxMultiple", "dsd_port", "dsd_native_format", "dsd_packing"):
            invalid = copy.deepcopy(recipe)
            if key == "dsdMaxMultiple":
                invalid.pop(key)
            else:
                invalid["settings"][key] = "auto"
            with self.subTest(key=key), self.assertRaisesRegex(service.Invalid, "explicit_route_and_bound"):
                service.validate_recipe(invalid)
        for bound in (True, 0, 65, 1024):
            invalid = copy.deepcopy(recipe)
            invalid["dsdMaxMultiple"] = bound
            with self.subTest(bound=bound), self.assertRaises(service.Invalid):
                service.validate_recipe(invalid)
        passthrough = copy.deepcopy(recipe)
        passthrough["settings"].update(dsd_port="passthrough", dsd_native_format="auto", dsd_format="0x1a000000")
        self.assertEqual(passthrough, service.validate_recipe(passthrough))
        passthrough["settings"]["dsd_format"] = "auto"
        with self.assertRaisesRegex(service.Invalid, "explicit_route_and_bound"):
            service.validate_recipe(passthrough)
        no_feature = copy.deepcopy(recipe)
        no_feature["status"] = "suggested"
        no_feature["requiredFeatures"] = ["pcm-direct-v1"]
        with self.assertRaisesRegex(service.Invalid, "bound_requires_feature"):
            service.validate_recipe(no_feature)

    def test_backup_restore_preserves_receipts_evidence_and_publication(self):
        receipt = self.store.receive(self.report)
        self.store.review(self.decision(receipt))
        manifest = self.store.publish()
        backup = Path(self.temp.name) / "recovery.sqlite3"
        self.store.backup(backup)
        restored = service.Store(backup)
        self.assertEqual({"status": "ok", "reportCount": 1, "publicationCount": 1}, restored.check())
        self.assertEqual(receipt, restored.receive(self.report))
        self.assertEqual(manifest, restored.manifest())
        self.assertEqual(self.store.snapshot(1), restored.snapshot(1))
        self.assertEqual(1, len(restored.evidence(self.recipe)))
        with self.assertRaises(FileExistsError):
            self.store.backup(backup)

    def test_new_negative_after_review_blocks_publication_until_reviewed(self):
        receipt = self.store.receive(self.report)
        self.store.review(self.decision(receipt))
        failure = copy.deepcopy(self.report)
        failure["reportId"] = str(uuid.uuid4())
        failure["attempts"][0]["hearing"] = "noise"
        self.store.receive(failure)
        with self.assertRaisesRegex(service.Invalid, "new_conflicts"):
            self.store.publish()
        self.assertIsNone(self.store.manifest())
        self.store.review(self.decision(receipt, acknowledge=True))
        self.assertEqual(1, self.store.publish()["sequence"])

    def test_repeated_history_in_new_reports_is_one_observation_with_all_receipts(self):
        first = self.store.receive(self.report)
        another = copy.deepcopy(self.report)
        another["reportId"] = str(uuid.uuid4())
        second = self.store.receive(another)
        observations = self.store.evidence(self.recipe)
        self.assertEqual(1, len(observations))
        self.assertEqual(2, len(observations[0]["references"]))
        self.assertEqual(first["receiptId"], observations[0]["receiptId"])
        # Maintainers may cite either immutable report containing that snapshot.
        review = self.store.review(self.decision(second))
        self.assertEqual(2, review["reportCount"])
        self.assertEqual(1, review["observationSnapshotCount"])
        self.assertEqual(1, self.store.publish()["sequence"])

    def test_changed_snapshot_of_same_attempt_preserves_old_evidence_and_conflict(self):
        first = self.store.receive(self.report)
        changed = copy.deepcopy(self.report)
        changed["reportId"] = str(uuid.uuid4())
        changed["attempts"][0]["hearing"] = "noise"
        second = self.store.receive(changed)
        observations = self.store.evidence(self.recipe)
        self.assertEqual(2, len(observations))
        self.assertEqual(["working", "noise"], [o["hearing"] for o in observations])
        self.assertNotEqual(observations[0]["snapshotDigest"], observations[1]["snapshotDigest"])
        with self.assertRaisesRegex(service.Invalid, "conflicts"):
            self.store.review(self.decision(first))
        review = self.store.review(self.decision(first, acknowledge=True))
        self.assertEqual(1, len(review["conflicts"]))
        self.assertEqual(second["receiptId"], review["conflicts"][0]["receiptId"])
        # Repeating the already-reviewed negative history is not a new conflict.
        repeated = copy.deepcopy(changed)
        repeated["reportId"] = str(uuid.uuid4())
        self.store.receive(repeated)
        self.assertEqual(1, self.store.publish()["sequence"])
        self.assertEqual(2, len(self.store.evidence(self.recipe)))

    def test_conflicts_require_review_and_report_flood_never_publishes(self):
        receipt = self.store.receive(self.report)
        for _ in range(20):
            report = copy.deepcopy(self.report)
            report["reportId"] = str(uuid.uuid4())
            self.store.receive(report)
        failure = copy.deepcopy(self.report)
        failure["reportId"] = str(uuid.uuid4())
        failure["attempts"][0]["hearing"] = "noise"
        self.store.receive(failure)
        self.assertIsNone(self.store.manifest())
        with self.assertRaisesRegex(service.Invalid, "conflicts"):
            self.store.review(self.decision(receipt))
        review = self.store.review(self.decision(receipt, acknowledge=True))
        self.assertEqual(22, review["reportCount"])
        self.assertEqual(1, len(review["conflicts"]))
        self.assertNotIn("uniqueDeviceCount", review)

    def test_revision_immutable_withdrawal_new_publication_and_retention(self):
        receipt = self.store.receive(self.report)
        self.store.review(self.decision(receipt))
        self.store.publish()
        old = self.store.snapshot(1)
        withdrawn = copy.deepcopy(self.recipe)
        withdrawn.update(status="withdrawn", reason="Qualification withdrawn pending conflicting evidence review.")
        self.store.review(self.decision(receipt, withdrawn))
        self.assertEqual(2, self.store.publish()["sequence"])
        self.assertEqual(old, self.store.snapshot(1))
        self.assertEqual("withdrawn", service.parse_json(self.store.snapshot(2))["recipes"][0]["status"])
        self.assertEqual(1, self.store.purge(now=int(time.time()) + 91 * 86400))
        self.assertEqual([], self.store.reports())
        self.assertEqual(old, self.store.snapshot(1))
        self.assertEqual(receipt, self.store.receive(self.report))
        self.assertEqual([], self.store.reports())  # Tombstone does not resurrect body.


class HTTPTests(EvidenceTests):
    def setUp(self):
        super().setUp()
        self.server = service.Server(("127.0.0.1", 0), self.store)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.port = self.server.server_address[1]

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        super().tearDown()

    def request(self, method, path, body=None, headers=None):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=5)
        conn.request(method, path, body=body, headers=headers or {})
        response = conn.getresponse()
        data = response.read()
        status = response.status
        conn.close()
        return status, data

    def post(self, report=None):
        return self.request("POST", "/v1/reports", json.dumps(report or self.report), {"Content-Type": "application/json"})

    def test_full_receipt_review_publication_second_client_loop(self):
        status, data = self.post()
        self.assertEqual(200, status)
        receipt = json.loads(data)
        self.assertEqual("received", receipt["status"])
        self.assertEqual(404, self.request("GET", "/catalog/manifest.json")[0])
        self.store.review(self.decision(receipt))
        self.store.publish()
        # A separate endpoint reads public catalog only; no report identity needed.
        status, data = self.request("GET", "/catalog/manifest.json")
        self.assertEqual(200, status)
        manifest = json.loads(data)
        status, snapshot = self.request("GET", manifest["snapshot"])
        self.assertEqual(200, status)
        self.assertEqual(manifest["sha256"], hashlib.sha256(snapshot).hexdigest())
        catalog = json.loads(snapshot)
        candidates = [r for r in catalog["recipes"] if service.in_cohort(self.report["attempts"][0], r)]
        self.assertEqual([self.recipe], candidates)
        self.assertNotIn("hearing", candidates[0])  # publication is never local hearing truth
        self.assertEqual(receipt, json.loads(self.post()[1]))

    def test_committed_then_lost_ack_retry_deduplicates(self):
        body = service.canonical(self.report)
        sock = socket.create_connection(("127.0.0.1", self.port))
        sock.sendall(b"POST /v1/reports HTTP/1.0\r\nContent-Type: application/json\r\nContent-Length: " + str(len(body)).encode() + b"\r\n\r\n" + body)
        sock.shutdown(socket.SHUT_WR)
        sock.close()
        deadline = time.monotonic() + 3
        while not self.store.reports() and time.monotonic() < deadline:
            time.sleep(0.01)
        self.assertEqual(1, len(self.store.reports()))
        status, data = self.post()
        self.assertEqual(200, status)
        self.assertEqual(self.store.reports()[0]["receipt_id"], json.loads(data)["receiptId"])
        self.assertEqual(1, len(self.store.reports()))

    def test_http_limits_private_logs_and_conflict(self):
        logs = io.StringIO()
        with redirect_stderr(logs):
            self.assertEqual(200, self.post()[0])
            modified = copy.deepcopy(self.report)
            modified["attempts"][0]["hearing"] = "silence"
            self.assertEqual(409, self.post(modified)[0])
            self.assertEqual(413, self.request("POST", "/v1/reports", b"x" * (service.MAX_BYTES + 1), {"Content-Type": "application/json"})[0])
            self.assertEqual(415, self.request("POST", "/v1/reports", b"{}", {"Content-Type": "text/plain"})[0])
            self.assertEqual(404, self.request("GET", "/unknown/Secret-Track-Name")[0])
        self.assertEqual("", logs.getvalue())
        self.server.limiter = service.RateLimiter(limit=1)
        self.assertEqual(200, self.post()[0])
        self.assertEqual(429, self.post()[0])

    def test_full_database_returns_retryable_error_and_preserves_catalog_reads(self):
        # Exercise SQLite's real full-database error with a small test ceiling;
        # production applies the same per-connection guard at 256 MiB.
        self.store.max_bytes = 128 * 1024
        status, body = self.post()
        self.assertEqual(200, status)
        self.store.review(self.decision(json.loads(body)))
        manifest = self.store.publish()
        old_snapshot = self.store.snapshot(manifest["sequence"])
        full_status = None
        for _ in range(20):
            report = copy.deepcopy(self.report)
            report["reportId"] = str(uuid.uuid4())
            report["attempts"] = []
            for order in range(32):
                attempt = copy.deepcopy(self.report["attempts"][0])
                attempt.update(id=str(uuid.uuid4()), order=order)
                report["attempts"].append(attempt)
            status, body = self.post(report)
            if status == 503:
                full_status = status
                self.assertEqual("temporarily_unavailable", json.loads(body)["error"])
                break
            self.assertEqual(200, status)
        self.assertEqual(503, full_status)
        self.assertEqual(200, self.request("GET", "/healthz")[0])
        self.assertEqual(manifest, json.loads(self.request("GET", "/catalog/manifest.json")[1]))
        status, snapshot = self.request("GET", manifest["snapshot"])
        self.assertEqual(200, status)
        self.assertEqual(old_snapshot, snapshot)
        self.assertEqual("ok", self.store.check()["status"])


if __name__ == "__main__":
    unittest.main()
