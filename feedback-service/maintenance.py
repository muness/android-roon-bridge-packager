#!/usr/bin/env python3
"""Daily report expiry and verified SQLite recovery points; no request logging."""
import argparse
import json
from pathlib import Path
import re
import time
import uuid

from feedback_service import Store

BACKUP_NAME = re.compile(r"evidence-[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}\.sqlite3\Z")
BACKUP_DAYS = 7


def maintain(database, directory, now=None):
    now = time.time() if now is None else now
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    store = Store(database)
    expired = store.purge(now=int(now))
    store.check()
    name = "evidence-" + time.strftime("%Y%m%dT%H%M%SZ", time.gmtime(now)) + "-" + uuid.uuid4().hex[:8] + ".sqlite3"
    backup = directory / name
    store.backup(backup)
    Store(backup).check()
    removed = 0
    owned = [path for path in directory.iterdir() if BACKUP_NAME.fullmatch(path.name) and path.is_file()]
    retained_days = set(sorted({path.name[9:17] for path in owned}, reverse=True)[:BACKUP_DAYS])
    for path in owned:
        if path.name[9:17] not in retained_days or path.stat().st_mtime <= now - BACKUP_DAYS * 86400 or (path != backup and path.name[9:17] == backup.name[9:17]):
            path.unlink()
            removed += 1
    return {"status": "ok", "expiredReportBodies": expired, "backupVerified": True, "expiredBackups": removed}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", required=True)
    parser.add_argument("--backups", required=True)
    args = parser.parse_args()
    print(json.dumps(maintain(args.db, args.backups)))


if __name__ == "__main__":
    main()
