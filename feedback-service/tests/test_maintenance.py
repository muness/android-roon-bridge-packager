import importlib.util
import os
from pathlib import Path
import sys
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
SPEC = importlib.util.spec_from_file_location("maintenance", ROOT / "maintenance.py")
maintenance = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(maintenance)


class MaintenanceTests(unittest.TestCase):
    def test_verifies_real_recovery_point_and_expires_only_owned_old_backups(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp) / "backups"
            directory.mkdir()
            old = directory / "evidence-20200101T000000Z-12345678.sqlite3"
            old.write_bytes(b"expired recovery point")
            unrelated = directory / "important.sqlite3"
            unrelated.write_bytes(b"unrelated operator file")
            now = time.time()
            os.utime(old, (now - 8 * 86400, now - 8 * 86400))
            os.utime(unrelated, (now - 8 * 86400, now - 8 * 86400))
            result = maintenance.maintain(Path(temp) / "evidence.sqlite3", directory, now)
            self.assertEqual({"status": "ok", "expiredReportBodies": 0, "backupVerified": True, "expiredBackups": 1}, result)
            self.assertFalse(old.exists())
            self.assertTrue(unrelated.exists())
            recovery = [p for p in directory.iterdir() if maintenance.BACKUP_NAME.fullmatch(p.name)]
            self.assertEqual(1, len(recovery))
            self.assertEqual("ok", maintenance.Store(recovery[0]).check()["status"])
            # Repeated invocation verifies a new recovery point, then retains one
            # for this UTC day instead of accumulating unlimited manual backups.
            maintenance.maintain(Path(temp) / "evidence.sqlite3", directory, now)
            self.assertEqual(1, len([p for p in directory.iterdir() if maintenance.BACKUP_NAME.fullmatch(p.name)]))

    def test_keeps_at_most_seven_daily_points_even_with_jittered_timestamps(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp) / "backups"
            directory.mkdir()
            now = time.time()
            for days in range(1, 9):
                day = time.strftime("%Y%m%dT030000Z", time.gmtime(now - days * 86400))
                path = directory / ("evidence-" + day + "-12345678.sqlite3")
                path.write_bytes(b"owned historical recovery point")
                os.utime(path, (now, now))
            maintenance.maintain(Path(temp) / "evidence.sqlite3", directory, now)
            self.assertEqual(7, len([p for p in directory.iterdir() if maintenance.BACKUP_NAME.fullmatch(p.name)]))
