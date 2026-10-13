#!/usr/bin/env python3
"""Check a packager APK, never a locally packed endpoint APK."""
import sys
import zipfile

with zipfile.ZipFile(sys.argv[1]) as apk:
    for name in apk.namelist():
        data = apk.read(name)
        lower = name.lower()
        if name.startswith('lib/') or any(x in lower for x in ('roon-tree', 'roon-exec-map', 'raatserver', 'roonbridge.dll', 'roonbridge.exe')):
            raise SystemExit(f'Forbidden runtime entry: {name}')
        if data.startswith((b'\x7fELF', b'MZ', b'BZh', b'!<arch>\n', b'\xfd7zXZ')):
            raise SystemExit(f'Forbidden runtime/archive payload: {name}')
print('Packager APK: no native runtime or vendor archive detected')
