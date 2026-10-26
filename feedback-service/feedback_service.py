#!/usr/bin/env python3
"""Playback evidence receiver and deliberately reviewed static recipe publisher.

Original work is governed by the repository's Personal Noncommercial License.
Only Python's standard library is required. No vendor runtime is used.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MAX_BYTES = 131072
MAX_ATTEMPTS = 32
RETENTION_DAYS = 90
MAX_DB_BYTES = 256 * 1024 * 1024
IDENTIFIER = re.compile(r"[A-Za-z0-9][A-Za-z0-9 ._()+-]{0,127}\Z", re.ASCII)
TOKEN = re.compile(r"[A-Za-z0-9][A-Za-z0-9._+-]{0,127}\Z", re.ASCII)
FEATURES = {"pcm-direct-v1", "usb-pcm-v1", "dsd-reviewed-v1"}
SETTINGS = {
    "sink": {"auto", "internal", "direct", "usb"},
    "prefer_internal": {"on", "off"},
    "force_encoding": {"auto", "16", "24", "32"},
    "force_rate": {"auto", "44100", "48000", "88200", "96000", "176400", "192000", "352800", "384000", "705600", "768000"},
    "direct_route": {"system", "probed"},
    "direct_buffer": {"stream", "fixed"},
    "direct_guard": {"on", "off"},
    "dsd_port": {"auto", "offload", "passthrough", "vendor"},
    "dsd_packing": {"auto", "u8", "u32le", "u32be", "u8_lsb", "u32be_lsb"},
    "dop_ceiling": {"verified", "off"},
    "verified_only": {"on", "off"},
    "feedback": {"on", "off"},
}
SAMPLE_FORMATS = {"S16_LE", "S24_LE", "S24_3LE", "S32_LE", "FLOAT_LE", "DSD_U8", "DSD_U16_LE", "DSD_U16_BE", "DSD_U32_LE", "DSD_U32_BE", "unknown"}
HEARING = {"unanswered", "working", "stutters", "noise", "silence", "unsure", "other"}
FAULTS = {"none", "open_refused", "audio_server_lost", "crash_suspected", "interrupted", "output_disconnected"}


class Invalid(ValueError):
    pass


class Conflict(ValueError):
    pass


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode("ascii")


def digest(value):
    return hashlib.sha256(canonical(value)).hexdigest()


def object_keys(value, required, optional=()):
    if type(value) is not dict or not set(required) <= value.keys() or value.keys() - set(required) - set(optional):
        raise Invalid("invalid_fields")


def integer(value, low=0, high=2147483647):
    if type(value) is not int or not low <= value <= high:
        raise Invalid("invalid_integer")


def identifier(value, token=False):
    if type(value) is not str or not (TOKEN if token else IDENTIFIER).fullmatch(value):
        raise Invalid("invalid_identifier")


def choice(value, values):
    if type(value) is not str or value not in values:
        raise Invalid("invalid_enum")


def uuid_string(value):
    if type(value) is not str:
        raise Invalid("invalid_id")
    try:
        parsed = uuid.UUID(value)
        if parsed.version != 4 or str(parsed) != value:
            raise ValueError()
    except ValueError:
        raise Invalid("invalid_id") from None


REPORT_ONLY_SETTINGS = {"test_mode", "assume_high_speed"}


def settings(value, report=False):
    if type(value) is not dict or not value or len(value) > len(SETTINGS) + 2 + (2 if report else 0):
        raise Invalid("invalid_settings")
    for key, setting in value.items():
        if key in {"dsd_format", "dsd_native_format"}:
            if type(setting) is not str:
                raise Invalid("invalid_setting")
            if setting != "auto":
                if not re.fullmatch(r"(?:0x[0-9a-f]{1,8}|[0-9]{1,10})", setting) or int(setting, 16 if setting.lower().startswith("0x") else 10) > 4294967295:
                    raise Invalid("invalid_setting")
        elif report and key in REPORT_ONLY_SETTINGS:
            choice(setting, {"on", "off"})
        elif report and key == "sink" and setting == "smoke":
            pass  # Record the truth, never publish it as a direct-output recipe.
        elif key in SETTINGS:
            choice(setting, SETTINGS[key])
        else:
            raise Invalid("unknown_setting")


def audio_format(value):
    object_keys(value, {"encoding", "rate", "channels"}, {"sampleFormat"})
    choice(value["encoding"], {"pcm", "dop", "dsd", "unknown"})
    integer(value["rate"], 0, 100000000)
    integer(value["channels"], 0, 32)
    if "sampleFormat" in value:
        choice(value["sampleFormat"], SAMPLE_FORMATS)


ATTEMPT_REQUIRED = {"id", "order", "manufacturer", "model", "sdk", "endpoint", "endpointVersion", "engineVersion", "output", "catalogSequence", "recipeId", "recipeRevision", "settings", "requested", "actual", "grant", "hearing", "fault", "elapsedMs"}
ATTEMPT_OPTIONAL = {"firmware", "usbVendorId", "usbProductId"}


def validate_attempt(value):
    object_keys(value, ATTEMPT_REQUIRED, ATTEMPT_OPTIONAL)
    uuid_string(value["id"])
    integer(value["order"], 0, 1000000)
    for key in ("manufacturer", "model"):
        identifier(value[key])
    for key in ("endpointVersion", "engineVersion", "recipeId"):
        identifier(value[key], token=True)
    if "firmware" in value:
        identifier(value["firmware"], token=True)
    integer(value["sdk"], 25, 100)
    choice(value["endpoint"], {"roon", "naa", "lab"})
    choice(value["output"], {"internal", "usb"})
    for key in ("usbVendorId", "usbProductId"):
        if key in value:
            integer(value[key], 0, 65535)
            if value["output"] != "usb":
                raise Invalid("unexpected_usb_identifier")
    integer(value["catalogSequence"])
    integer(value["recipeRevision"])
    settings(value["settings"], report=True)
    audio_format(value["requested"])
    if value["actual"] is not None:
        audio_format(value["actual"])
    choice(value["grant"], {"unknown", "opened", "refused"})
    choice(value["hearing"], HEARING)
    choice(value["fault"], FAULTS)
    integer(value["elapsedMs"], 0, 604800000)
    # Preserve contradictory evidence as reported; do not infer hearing from a grant.


def validate_report(value):
    object_keys(value, {"schemaVersion", "reportId", "consent", "attempts"})
    integer(value["schemaVersion"], 1, 1)
    uuid_string(value["reportId"])
    choice(value["consent"], {"playback-results"})
    attempts = value["attempts"]
    if type(attempts) is not list or not 1 <= len(attempts) <= MAX_ATTEMPTS:
        raise Invalid("invalid_attempt_count")
    ids, orders = set(), set()
    for attempt in attempts:
        validate_attempt(attempt)
        if attempt["id"] in ids or attempt["order"] in orders:
            raise Invalid("duplicate_attempt")
        ids.add(attempt["id"])
        orders.add(attempt["order"])
    if len(canonical(value)) > MAX_BYTES:
        raise Invalid("payload_too_large")
    return value


def parse_json(data):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise Invalid("duplicate_field")
            result[key] = value
        return result
    try:
        return json.loads(data.decode("utf-8"), object_pairs_hook=pairs,
                          parse_constant=lambda _: (_ for _ in ()).throw(Invalid("invalid_number")))
    except (UnicodeError, json.JSONDecodeError, RecursionError):
        raise Invalid("invalid_json") from None


RECIPE_REQUIRED = {"id", "revision", "label", "status", "manufacturer", "model", "minSdk", "maxSdk", "endpoint", "output", "requiredFeatures", "settings", "reason"}
RECIPE_OPTIONAL = {"firmware", "engineVersion", "usbVendorId", "usbProductId", "dsdMaxMultiple"}


def native_recipe(recipe):
    return "dsdMaxMultiple" in recipe or "dsd-reviewed-v1" in recipe["requiredFeatures"] or any(recipe["settings"].get(key, "auto") != "auto" for key in ("dsd_port", "dsd_native_format", "dsd_packing"))


def validate_recipe(recipe):
    object_keys(recipe, RECIPE_REQUIRED, RECIPE_OPTIONAL)
    identifier(recipe["id"], token=True)
    if len(recipe["id"]) > 80 or not re.fullmatch(r"[A-Za-z0-9._-]+", recipe["id"]):
        raise Invalid("invalid_recipe_id")
    integer(recipe["revision"], 1)
    for key in ("label", "reason"):
        if type(recipe[key]) is not str or not 1 <= len(recipe[key]) <= 512 or any(ord(c) < 32 for c in recipe[key]):
            raise Invalid("invalid_description")
    if len(recipe["label"]) > 120:
        raise Invalid("invalid_label")
    choice(recipe["status"], {"recommended", "suggested", "withdrawn", "excluded"})
    for key in ("manufacturer", "model"):
        if recipe[key] != "*":
            identifier(recipe[key])
            if len(recipe[key]) > 100:
                raise Invalid("invalid_model_scope")
    for key in ("firmware", "engineVersion"):
        if key in recipe:
            identifier(recipe[key], token=True)
            if key == "engineVersion" and len(recipe[key]) > 80:
                raise Invalid("invalid_engine_scope")
    integer(recipe["minSdk"], 25, 100)
    integer(recipe["maxSdk"], recipe["minSdk"], 100)
    choice(recipe["endpoint"], {"roon", "naa", "lab", "*"})
    choice(recipe["output"], {"internal", "usb"})
    for key in ("usbVendorId", "usbProductId"):
        if key in recipe:
            integer(recipe[key], 1, 65535)
            if recipe["output"] != "usb":
                raise Invalid("unexpected_usb_identifier")
    if "usbProductId" in recipe and "usbVendorId" not in recipe:
        raise Invalid("usb_vendor_required")
    if recipe["manufacturer"] == "*" and recipe["model"] != "*":
        raise Invalid("model_requires_manufacturer")
    if type(recipe["requiredFeatures"]) is not list or len(recipe["requiredFeatures"]) > 8 or any(type(f) is not str for f in recipe["requiredFeatures"]):
        raise Invalid("invalid_features")
    if len(set(recipe["requiredFeatures"])) != len(recipe["requiredFeatures"]):
        raise Invalid("invalid_features")
    for feature in recipe["requiredFeatures"]:
        choice(feature, FEATURES)
    settings(recipe["settings"])
    if "dsdMaxMultiple" in recipe:
        integer(recipe["dsdMaxMultiple"], 64, 512)
        if recipe["dsdMaxMultiple"] not in {64, 128, 256, 512}:
            raise Invalid("invalid_dsd_bound")
        if "dsd-reviewed-v1" not in recipe["requiredFeatures"]:
            raise Invalid("dsd_bound_requires_feature")
    if recipe["status"] == "recommended" and (recipe["manufacturer"] == "*" or recipe["model"] == "*" or recipe["endpoint"] == "*" or recipe["minSdk"] != recipe["maxSdk"] or "engineVersion" not in recipe):
        raise Invalid("recommended_requires_exact_cohort")
    if recipe["status"] == "recommended" and native_recipe(recipe):
        selected_format = "dsd_format" if recipe["settings"].get("dsd_port") == "passthrough" else "dsd_native_format"
        if "dsdMaxMultiple" not in recipe or "dsd-reviewed-v1" not in recipe["requiredFeatures"] or any(recipe["settings"].get(key, "auto") == "auto" for key in ("dsd_port", selected_format, "dsd_packing")):
            raise Invalid("native_dsd_requires_explicit_route_and_bound")
    return recipe


def validate_catalog(catalog):
    object_keys(catalog, {"schema", "resolver", "sequence", "requiredFeatures", "recipes"})
    integer(catalog["schema"], 1, 1)
    integer(catalog["resolver"], 1, 1)
    integer(catalog["sequence"])
    if type(catalog["requiredFeatures"]) is not list or any(type(f) is not str or f not in FEATURES for f in catalog["requiredFeatures"]):
        raise Invalid("invalid_features")
    if type(catalog["recipes"]) is not list or len(catalog["recipes"]) > 256:
        raise Invalid("invalid_recipes")
    ids = set()
    for recipe in catalog["recipes"]:
        validate_recipe(recipe)
        if recipe["id"] in ids:
            raise Invalid("duplicate_recipe")
        ids.add(recipe["id"])
    if len(canonical(catalog)) > 262144:
        raise Invalid("catalog_too_large")
    return catalog


def published_settings(attempt):
    return {k: v for k, v in attempt["settings"].items() if k not in REPORT_ONLY_SETTINGS}


def in_cohort(attempt, recipe):
    for key in ("manufacturer", "model", "endpoint"):
        if recipe[key] != "*" and attempt[key].casefold() != recipe[key].casefold():
            return False
    if attempt["output"] != recipe["output"] or not recipe["minSdk"] <= attempt["sdk"] <= recipe["maxSdk"]:
        return False
    for key in ("firmware", "engineVersion", "usbVendorId", "usbProductId"):
        if key in recipe and attempt.get(key) != recipe[key]:
            return False
    return True


def negative(attempt):
    return attempt["hearing"] in {"stutters", "noise", "silence", "other"} or attempt["fault"] not in {"none", "interrupted", "output_disconnected"} or attempt["grant"] == "refused"


def qualifies(attempt, recipe):
    actual = attempt["actual"]
    if attempt["hearing"] != "working" or attempt["grant"] != "opened" or attempt["fault"] != "none" or actual is None or actual["encoding"] == "unknown":
        return False
    if any(attempt["settings"].get(key, "off") != "off" for key in REPORT_ONLY_SETTINGS):
        return False
    if native_recipe(recipe):
        bits = {"DSD_U8": 8, "DSD_U16_LE": 16, "DSD_U16_BE": 16, "DSD_U32_LE": 32, "DSD_U32_BE": 32}.get(actual.get("sampleFormat"), 0)
        bit_rate = actual["rate"] * bits
        # This bounds the reviewed candidate; it does not mark lower rates heard.
        return actual["encoding"] == "dsd" == attempt["requested"]["encoding"] and bits > 0 and bit_rate % 44100 == 0 and bit_rate // 44100 >= recipe["dsdMaxMultiple"]
    return True


class Store:
    def __init__(self, path, max_bytes=MAX_DB_BYTES):
        self.path = str(path)
        self.max_bytes = max_bytes
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as db:
            db.executescript("""
                PRAGMA journal_mode=WAL;
                CREATE TABLE IF NOT EXISTS reports (
                    report_id TEXT PRIMARY KEY, receipt_id TEXT UNIQUE NOT NULL,
                    digest TEXT NOT NULL, payload BLOB,
                    received INTEGER NOT NULL, expires INTEGER NOT NULL);
                CREATE TABLE IF NOT EXISTS reviews (
                    decision_id TEXT PRIMARY KEY, created INTEGER NOT NULL,
                    payload BLOB NOT NULL);
                CREATE TABLE IF NOT EXISTS publications (
                    sequence INTEGER PRIMARY KEY, created INTEGER NOT NULL,
                    digest TEXT NOT NULL, snapshot BLOB NOT NULL);
            """)
        os.chmod(path, 0o600)

    def connect(self):
        db = sqlite3.connect(self.path, timeout=15)
        db.row_factory = sqlite3.Row
        page_size = db.execute("PRAGMA page_size").fetchone()[0]
        target_pages = self.max_bytes // page_size
        # Apply on every connection: a fresh SQLite connection otherwise restores
        # its default maximum. Existing snapshots remain readable at the ceiling.
        actual_limit = db.execute("PRAGMA max_page_count=%d" % target_pages).fetchone()[0]
        if actual_limit > target_pages:
            db.close()
            raise sqlite3.OperationalError("storage_capacity_exceeded")
        return db

    def receive(self, report):
        validate_report(report)
        content_digest = digest(report)
        now = int(time.time())
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            old = db.execute("SELECT receipt_id,digest FROM reports WHERE report_id=?", (report["reportId"],)).fetchone()
            if old:
                if old["digest"] != content_digest:
                    raise Conflict("report_id_reused")
                receipt = old["receipt_id"]
            else:
                receipt = str(uuid.uuid4())
                db.execute("INSERT INTO reports VALUES(?,?,?,?,?,?)", (report["reportId"], receipt, content_digest, canonical(report), now, now + RETENTION_DAYS * 86400))
        return {"receiptId": receipt, "reportId": report["reportId"], "status": "received"}

    def reports(self):
        with self.connect() as db:
            return [dict(row) for row in db.execute("SELECT receipt_id,report_id,received,expires,payload FROM reports WHERE payload IS NOT NULL ORDER BY rowid")]

    def evidence(self, recipe):
        observations = {}
        for row in self.reports():
            for attempt in parse_json(row["payload"])["attempts"]:
                if in_cohort(attempt, recipe) and published_settings(attempt) == recipe["settings"]:
                    # A new session report can include earlier attempts again. Retry
                    # identity belongs to the report; evidence identity belongs to an
                    # attempt snapshot. Changed snapshots remain separate evidence.
                    snapshot_digest = digest(attempt)
                    key = (attempt["id"], snapshot_digest)
                    ref = {"receiptId": row["receipt_id"], "attemptId": attempt["id"]}
                    if key not in observations:
                        observations[key] = {**ref, "snapshotDigest": snapshot_digest,
                            "hearing": attempt["hearing"], "grant": attempt["grant"],
                            "fault": attempt["fault"], "endpoint": attempt["endpoint"],
                            "negative": negative(attempt), "attempt": attempt, "references": []}
                    observations[key]["references"].append(ref)
        return list(observations.values())

    def review(self, decision):
        object_keys(decision, {"reviewer", "reason", "recipe", "evidence", "acknowledgeConflicts"})
        identifier(decision["reviewer"], token=True)
        if type(decision["reason"]) is not str or not 1 <= len(decision["reason"]) <= 1000:
            raise Invalid("invalid_review_reason")
        if type(decision["acknowledgeConflicts"]) is not bool:
            raise Invalid("invalid_acknowledgment")
        recipe = validate_recipe(decision["recipe"])
        refs = decision["evidence"]
        if type(refs) is not list or not 1 <= len(refs) <= 128:
            raise Invalid("evidence_required")
        observations = self.evidence(recipe)
        by_id = {(ref["receiptId"], ref["attemptId"]): o for o in observations for ref in o["references"]}
        cited = []
        for ref in refs:
            object_keys(ref, {"receiptId", "attemptId"})
            uuid_string(ref["receiptId"])
            uuid_string(ref["attemptId"])
            if (ref["receiptId"], ref["attemptId"]) not in by_id:
                raise Invalid("evidence_scope_or_settings_mismatch")
            cited.append(by_id[(ref["receiptId"], ref["attemptId"])])
        if any(o["negative"] for o in observations) and not decision["acknowledgeConflicts"]:
            raise Invalid("conflicts_must_be_acknowledged")
        if recipe["status"] == "recommended" and not any(qualifies(o["attempt"], recipe) for o in cited):
            raise Invalid("heard_stream_evidence_required")
        # Reviews remain separate from releases. No report count promotes a recipe.
        decision = dict(decision)
        decision["decisionId"] = str(uuid.uuid4())
        decision["reportCount"] = len({ref["receiptId"] for o in observations for ref in o["references"]})
        decision["observationSnapshotCount"] = len(observations)
        decision["conflicts"] = [{k: o[k] for k in ("receiptId", "attemptId", "snapshotDigest", "hearing", "grant", "fault")} for o in observations if o["negative"]]
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            for row in db.execute("SELECT payload FROM reviews"):
                old = parse_json(row[0])["recipe"]
                if old["id"] == recipe["id"] and old["revision"] == recipe["revision"] and old["settings"] != recipe["settings"]:
                    raise Invalid("recipe_revision_is_immutable")
                if old["id"] == recipe["id"] and old["revision"] > recipe["revision"]:
                    raise Invalid("recipe_revision_rollback")
            db.execute("INSERT INTO reviews VALUES(?,?,?)", (decision["decisionId"], int(time.time()), canonical(decision)))
        return decision

    def publish(self):
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            decisions = {}
            for row in db.execute("SELECT payload FROM reviews ORDER BY rowid"):
                decision = parse_json(row[0])
                decisions[decision["recipe"]["id"]] = decision
            if not decisions:
                raise Invalid("review_required")
            recipes = {}
            for key, decision in decisions.items():
                recipe = decision["recipe"]
                if recipe["status"] in {"recommended", "suggested"}:
                    acknowledged = {(ref["attemptId"], ref.get("snapshotDigest")) for ref in decision["conflicts"]}
                    if any(o["negative"] and (o["attemptId"], o["snapshotDigest"]) not in acknowledged for o in self.evidence(recipe)):
                        raise Invalid("new_conflicts_require_review")
                recipes[key] = recipe
            sequence = db.execute("SELECT COALESCE(MAX(sequence),0)+1 FROM publications").fetchone()[0]
            catalog = {"schema": 1, "resolver": 1, "sequence": sequence, "requiredFeatures": [], "recipes": [recipes[key] for key in sorted(recipes)]}
            validate_catalog(catalog)
            snapshot = canonical(catalog)
            db.execute("INSERT INTO publications VALUES(?,?,?,?)", (sequence, int(time.time()), hashlib.sha256(snapshot).hexdigest(), snapshot))
        return {"schema": 1, "sequence": sequence, "sha256": hashlib.sha256(snapshot).hexdigest(), "snapshot": "/catalog/%d.json" % sequence}

    def manifest(self):
        with self.connect() as db:
            row = db.execute("SELECT sequence,digest FROM publications ORDER BY sequence DESC LIMIT 1").fetchone()
        if not row:
            return None
        return {"schema": 1, "sequence": row["sequence"], "sha256": row["digest"], "snapshot": "/catalog/%d.json" % row["sequence"]}

    def snapshot(self, sequence):
        with self.connect() as db:
            row = db.execute("SELECT snapshot FROM publications WHERE sequence=?", (sequence,)).fetchone()
        return row[0] if row else None

    def purge(self, now=None):
        # Retain publication/review decision audit records; report bodies expire.
        with self.connect() as db:
            db.execute("PRAGMA secure_delete=ON")
            result = db.execute("UPDATE reports SET payload=NULL WHERE payload IS NOT NULL AND expires<=?", (int(time.time()) if now is None else now,))
        # A checkpoint bounds report remnants in the WAL, backups have own retention.
        with self.connect() as db:
            db.execute("PRAGMA wal_checkpoint(TRUNCATE)")
        return result.rowcount

    def backup(self, destination):
        destination = Path(destination)
        destination.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        # Fail rather than overwrite an earlier recovery point.
        descriptor = os.open(destination, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        os.close(descriptor)
        with self.connect() as source, sqlite3.connect(destination) as target:
            source.backup(target)
        return {"status": "backed_up"}

    def check(self):
        report_count = publication_count = 0
        with self.connect() as db:
            if db.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
                raise Invalid("database_integrity_failed")
            # Stream records so the verified-backup timer stays within its memory
            # budget even when the persistent database reaches its storage cap.
            for row in db.execute("SELECT digest,payload FROM reports WHERE payload IS NOT NULL"):
                if digest(validate_report(parse_json(row["payload"]))) != row["digest"]:
                    raise Invalid("report_digest_mismatch")
                report_count += 1
            for row in db.execute("SELECT sequence,digest,snapshot FROM publications"):
                if hashlib.sha256(row["snapshot"]).hexdigest() != row["digest"] or validate_catalog(parse_json(row["snapshot"]))["sequence"] != row["sequence"]:
                    raise Invalid("publication_digest_mismatch")
                publication_count += 1
        return {"status": "ok", "reportCount": report_count, "publicationCount": publication_count}


class RateLimiter:
    """Bounded in-memory source-address buckets, never written to disk or logs."""
    def __init__(self, limit=30, window=60, capacity=4096):
        self.limit, self.window, self.capacity = limit, window, capacity
        self.buckets = {}
        self.lock = threading.Lock()

    def allow(self, address):
        now = time.monotonic()
        with self.lock:
            self.buckets = {key: value for key, value in self.buckets.items() if now - value[0] < self.window}
            if address not in self.buckets:
                if len(self.buckets) >= self.capacity:
                    return False
                self.buckets[address] = (now, 0)
            start, count = self.buckets[address]
            self.buckets[address] = (start, count + 1)
            return count < self.limit


class Server(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 32

    def __init__(self, address, store, rate_limit=30):
        self.store = store
        self.limiter = RateLimiter(limit=rate_limit)
        self.slots = threading.BoundedSemaphore(32)
        super().__init__(address, Handler)

    def process_request(self, request, client_address):
        if not self.slots.acquire(blocking=False):
            request.close()
            return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self.slots.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.slots.release()

    def handle_error(self, request, client_address):
        # Default implementation includes addresses and arbitrary request context.
        sys.stderr.write("request_failed\n")


class Handler(BaseHTTPRequestHandler):
    server_version = "PlaybackEvidence/1"
    sys_version = ""
    protocol_version = "HTTP/1.0"

    def setup(self):
        super().setup()
        self.connection.settimeout(10)

    def log_message(self, fmt, *args):
        pass  # Never log IPs, request targets, report bodies or device identifiers.

    def send_error(self, code, message=None, explain=None):
        self.reply(code, {"error": "invalid_request"})

    def reply(self, code, value, immutable=False):
        data = value if type(value) is bytes else canonical(value)
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "public, max-age=31536000, immutable" if immutable else "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        try:
            self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError):
            pass  # A committed receipt survives a lost acknowledgement.

    def do_GET(self):
        if self.path == "/healthz":
            return self.reply(200, {"status": "ok"})
        if self.path == "/catalog/manifest.json":
            manifest = self.server.store.manifest()
            return self.reply(200, manifest) if manifest else self.reply(404, {"error": "no_catalog"})
        match = re.fullmatch(r"/catalog/([1-9][0-9]{0,9})\.json", self.path)
        if match:
            snapshot = self.server.store.snapshot(int(match.group(1)))
            if snapshot:
                return self.reply(200, snapshot, immutable=True)
        self.reply(404, {"error": "not_found"})

    def do_POST(self):
        if self.path != "/v1/reports":
            return self.reply(404, {"error": "not_found"})
        if not self.server.limiter.allow(self.client_address[0]):
            return self.reply(429, {"error": "rate_limited"})
        if self.headers.get("Transfer-Encoding") or self.headers.get_content_type() != "application/json":
            return self.reply(415, {"error": "json_required"})
        lengths = self.headers.get_all("Content-Length", [])
        if len(lengths) != 1 or not re.fullmatch(r"[0-9]{1,9}", lengths[0]):
            return self.reply(411, {"error": "content_length_required"})
        size = int(lengths[0])
        if size > MAX_BYTES:
            return self.reply(413, {"error": "payload_too_large"})
        try:
            data = self.rfile.read(size)
            if len(data) != size:
                raise Invalid("incomplete_body")
            result = self.server.store.receive(parse_json(data))
        except Invalid as error:
            return self.reply(400, {"error": str(error)})
        except Conflict as error:
            return self.reply(409, {"error": str(error)})
        except (TimeoutError, ConnectionError):
            return
        except sqlite3.Error:
            return self.reply(503, {"error": "temporarily_unavailable"})
        self.reply(200, result)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", default="local/feedback.sqlite3")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("init")
    serve = commands.add_parser("serve")
    serve.add_argument("--host", default="127.0.0.1")
    serve.add_argument("--port", type=int, default=8765)
    serve.add_argument("--rate-limit", type=int, default=30,
                       help="Requests/minute per direct peer; proxy must also limit original sources")
    commands.add_parser("list")
    show = commands.add_parser("show")
    show.add_argument("receipt")
    evidence = commands.add_parser("evidence")
    evidence.add_argument("recipe", type=Path)
    review = commands.add_parser("review")
    review.add_argument("decision", type=Path)
    commands.add_parser("publish")
    export = commands.add_parser("export")
    export.add_argument("directory", type=Path)
    commands.add_parser("purge")
    backup = commands.add_parser("backup")
    backup.add_argument("destination", type=Path)
    commands.add_parser("check")
    args = parser.parse_args(argv)
    store = Store(args.db)
    if args.command == "serve":
        if not 1 <= args.rate_limit <= 10000:
            raise Invalid("invalid_rate_limit")
        with Server((args.host, args.port), store, args.rate_limit) as server:
            server.serve_forever()
        return
    if args.command == "init":
        result = {"status": "initialized"}
    elif args.command == "list":
        result = [{k: row[k] for k in ("receipt_id", "report_id", "received", "expires")} for row in store.reports()]
    elif args.command == "show":
        matches = [row for row in store.reports() if row["receipt_id"] == args.receipt]
        if not matches:
            raise Invalid("unknown_receipt")
        result = parse_json(matches[0]["payload"])
    elif args.command == "evidence":
        recipe = validate_recipe(parse_json(args.recipe.read_bytes()))
        observations = store.evidence(recipe)
        result = {"reportCount": len({ref["receiptId"] for o in observations for ref in o["references"]}), "observationSnapshotCount": len(observations), "independentDeviceCount": None, "observations": [{k: v for k, v in o.items() if k != "attempt"} for o in observations]}
    elif args.command == "review":
        result = store.review(parse_json(args.decision.read_bytes()))
    elif args.command == "publish":
        result = store.publish()
    elif args.command == "export":
        manifest = store.manifest()
        if not manifest:
            raise Invalid("no_catalog")
        directory = args.directory / "catalog"
        directory.mkdir(parents=True, exist_ok=True)
        snapshot = directory / (str(manifest["sequence"]) + ".json")
        snapshot_bytes = store.snapshot(manifest["sequence"])
        if snapshot.exists() and snapshot.read_bytes() != snapshot_bytes:
            raise Invalid("immutable_snapshot_conflict")
        if not snapshot.exists():
            pending_snapshot = directory / (snapshot.name + ".part")
            pending_snapshot.write_bytes(snapshot_bytes)
            os.replace(pending_snapshot, snapshot)
        pending = directory / "manifest.json.part"
        pending.write_bytes(canonical(manifest))
        os.replace(pending, directory / "manifest.json")
        result = manifest
    elif args.command == "purge":
        result = {"deletedReports": store.purge()}
    elif args.command == "backup":
        result = store.backup(args.destination)
    elif args.command == "check":
        result = store.check()
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    try:
        main()
    except (Invalid, Conflict) as error:
        sys.stderr.write(str(error) + "\n")
        sys.exit(2)
