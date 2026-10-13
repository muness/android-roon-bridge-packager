#!/usr/bin/env python3
"""Check staged files and every reachable Git blob; no downloads."""
import json
import re
import subprocess
from pathlib import Path

root = Path(__file__).resolve().parents[1]
def git(*args):
    return subprocess.check_output(['git', '-C', str(root), *args], stderr=subprocess.DEVNULL)

def check(name, data):
    bad_name = re.search(r'\.(apk|aab|deb|jar|jks|p12|keystore|so|dll|exe|zip|tar|bz2|xz|gz|bin|pcap)$', name, re.I)
    bad_dir = re.search(r'(^|/)(build|artifacts|jniLibs|\.gradle|local)(/|$)', name)
    magic = data.startswith((b'\x7fELF', b'MZ', b'PK\x03\x04', b'!<arch>\n', b'BZh', b'\xfd7zXZ'))
    if bad_name or bad_dir or magic:
        raise SystemExit(f'Source-only violation: {name}')

for record in git('ls-files', '--stage', '-z').split(b'\0'):
    if not record: continue
    meta, name = record.split(b'\t', 1)
    mode, oid, stage = meta.split()
    if mode == b'160000': raise SystemExit('Submodules are not allowed in this source-only repository')
    check(name.decode(), git('cat-file', 'blob', oid.decode()))
try:
    git('rev-parse', '--verify', 'HEAD')
except subprocess.CalledProcessError:
    history = b''
else:
    history = git('rev-list', '--objects', '--all')
for line in history.splitlines():
    oid, _, name = line.partition(b' ')
    if git('cat-file', '-t', oid.decode()).strip() == b'blob':
        check(name.decode(), git('cat-file', 'blob', oid.decode()))

c = json.loads((root/'android/app/src/main/assets/catalogue.json').read_text())
assert len(c['endpoints']) == 1 and c['endpoints'][0]['id'] == 'roon'
e = c['endpoints'][0]
assert not e['vendorArchive'].get('pinOnFirstUse')
assert re.fullmatch(r'[0-9a-fA-F]{64}', e['vendorArchive']['sha256ByVersion'][e['vendorVersion']])
print('Source-only index, reachable history and Roon catalogue: clean')
