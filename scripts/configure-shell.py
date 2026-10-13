#!/usr/bin/env python3
"""Record an audited vendor-free shell without fetching it."""
import argparse
import json
import re
from pathlib import Path
from urllib.parse import urlparse

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--url', required=True)
parser.add_argument('--sha256', required=True)
parser.add_argument('--version', required=True)
args = parser.parse_args()
u = urlparse(args.url)
if u.scheme != 'https' or not u.hostname or u.username or u.password or u.hostname.endswith('.invalid'):
    parser.error('a real HTTPS shell URL without embedded credentials is required')
if not re.fullmatch(r'[0-9a-fA-F]{64}', args.sha256) or args.sha256 == '0' * 64:
    parser.error('an independently recorded SHA-256 is required')
if not args.version.strip():
    parser.error('a shell version is required')
p = Path(__file__).resolve().parents[1] / 'android/app/src/main/assets/catalogue.json'
data = json.loads(p.read_text())
data['endpoints'][0]['shell'].update(url=args.url, sha256=args.sha256.lower(), versionName=args.version)
p.write_text(json.dumps(data, indent=2) + '\n')
print('Catalogue updated. Review shell provenance, contents and this diff before building.')
