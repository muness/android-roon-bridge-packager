#!/usr/bin/env python3
"""Audit a shell APK against the catalogue; does not configure or publish it."""
import argparse
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import struct
import subprocess
import zipfile


CATALOGUE = Path(__file__).resolve().parents[1] / 'android/app/src/main/assets/catalogue.json'
SERVICE_KEY = 'io.github.muness.playback.SERVICE_URL'


def audit(apk_path, catalogue_path=CATALOGUE):
    catalogue = json.loads(Path(catalogue_path).read_text())
    endpoints = catalogue['endpoints']
    if len(endpoints) != 1 or endpoints[0]['id'] != 'roon':
        raise ValueError('expected exactly the Roon catalogue endpoint')
    plan = endpoints[0]['packPlan']
    own = plan['ownLibraries']
    if not own or len(set(own)) != len(own):
        raise ValueError('catalogue must declare distinct required own libraries')
    if any('/' in name or not re.fullmatch(r'lib[A-Za-z0-9_]+\.so', name) for name in own):
        raise ValueError('invalid catalogue own library name')
    required = {plan['nativeDir'] + name for name in own}
    child_launcher = plan['nativeDir'] + 'libroonchild_exec.so'
    forbidden_assets = {plan['treeAsset'].lower(), plan['mapAsset'].lower()}
    with open(apk_path, 'rb') as source:
        if source.read(4) != b'PK\x03\x04':
            raise ValueError('shell must begin with a ZIP local file header')
    with zipfile.ZipFile(apk_path) as apk:
        entries = apk.infolist()
        names = [entry.filename for entry in entries]
        if len(names) != len(set(names)):
            raise ValueError('duplicate ZIP entry')
        if 'AndroidManifest.xml' not in names or 'classes.dex' not in names:
            raise ValueError('shell must contain AndroidManifest.xml and classes.dex')
        missing = required - set(names)
        if missing:
            raise ValueError('missing required shell libraries: ' + ', '.join(sorted(missing)))
        if sum(entry.file_size for entry in entries) > 512 * 1024 * 1024:
            raise ValueError('shell exceeds the 512 MiB expanded audit limit')
        for entry in entries:
            name = entry.filename
            path = PurePosixPath(name)
            if ('\\' in name or name.startswith('/') or '..' in path.parts
                    or any(part in ('', '.') for part in name.rstrip('/').split('/'))
                    or any(ord(char) < 32 for char in name)):
                raise ValueError('unsafe ZIP path: ' + name)
            if entry.flag_bits & 1 or (entry.external_attr >> 16) & 0o170000 == 0o120000:
                raise ValueError('encrypted or symlink ZIP entry: ' + name)
            lower = name.lower()
            base = path.name.lower()
            if (lower in forbidden_assets or 'roon-tree' in lower or 'roon-exec-map' in lower
                    or base in ('raatserver', 'roonbridge', 'roonbridge.exe', 'roonbridge.dll',
                                'roonbridgehelper', 'libhostfxr.so', 'libcoreclr.so')
                    or lower.endswith(('_roon.so', '.tar.bz2', '.deb'))):
                raise ValueError('vendor runtime/tree entry: ' + name)
            if entry.is_dir():
                continue
            if name.startswith('lib/') and name not in required:
                raise ValueError('native entry is outside catalogue ownLibraries: ' + name)
            with apk.open(entry) as source:
                header = source.read(20)
                child = None
                if name == child_launcher:
                    if entry.file_size > 64 * 1024:
                        raise ValueError('child launcher exceeds the 64 KiB audit limit')
                    child = header + source.read(64 * 1024 + 1)
                # Reading the rest checks CRC without retaining the payload in memory.
                while source.read(1024 * 1024):
                    pass
            if name in required:
                if name == child_launcher:
                    try:
                        child.decode('utf-8')
                    except UnicodeDecodeError:
                        raise ValueError('child launcher must be UTF-8 text') from None
                    if (len(child) > 64 * 1024 or not child.startswith(b'#!/system/bin/sh\n')
                            or b'\0' in child):
                        raise ValueError('child launcher must be a bounded /system/bin/sh script without NUL')
                elif (len(header) < 20 or header[:6] != b'\x7fELF\x02\x01'
                        or struct.unpack_from('<H', header, 18)[0] != 183):
                    raise ValueError('required library is not little-endian ELF64 AArch64: ' + name)
            elif header.startswith((b'\x7fELF', b'MZ', b'BZh', b'!<arch>\n', b'\xfd7zXZ')):
                raise ValueError('runtime/archive payload outside ownLibraries: ' + name)
    return len(required)


def manifest_service_url(dump):
    """Read the value on the same meta-data element in aapt2 xmltree output."""
    matches = []
    attrs = None
    indent = 0
    for line in dump.splitlines() + ['E: end']:
        stripped = line.lstrip()
        depth = len(line) - len(stripped)
        if attrs is not None and stripped.startswith('E:') and depth <= indent:
            if attrs.get('name') == SERVICE_KEY:
                matches.append(attrs.get('value'))
            attrs = None
        if re.match(r'E: meta-data(?:\s|$)', stripped):
            attrs, indent = {}, depth
        elif attrs is not None:
            match = re.match(r'A: (?:android:|http://schemas.android.com/apk/res/android:)(name|value)\([^)]*\)="([^"]*)"', stripped)
            if match:
                attrs[match.group(1)] = match.group(2)
    if len(matches) != 1 or matches[0] is None:
        raise ValueError('manifest must contain exactly one literal playback SERVICE_URL value')
    return matches[0]


def find_aapt2(explicit):
    if explicit:
        return explicit
    found = shutil.which('aapt2')
    if found:
        return found
    for key in ('ANDROID_HOME', 'ANDROID_SDK_ROOT'):
        root = os.environ.get(key)
        if root:
            tools = sorted(Path(root).glob('build-tools/*/aapt2'), reverse=True)
            if tools:
                return str(tools[0])
    raise ValueError('--service-url verification requires aapt2; supply --aapt2 PATH')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--catalogue', type=Path, default=CATALOGUE)
    parser.add_argument('--service-url', help='require this exact literal manifest SERVICE_URL')
    parser.add_argument('--aapt2', help='Android build-tools aapt2 executable')
    args = parser.parse_args()
    try:
        count = audit(args.apk, args.catalogue)
        if args.service_url is not None:
            result = subprocess.run([find_aapt2(args.aapt2), 'dump', 'xmltree',
                                     str(args.apk), '--file', 'AndroidManifest.xml'],
                                    check=True, capture_output=True, text=True, timeout=30)
            if manifest_service_url(result.stdout) != args.service_url:
                raise ValueError('manifest playback SERVICE_URL does not match expected URL')
    except (OSError, ValueError, KeyError, zipfile.BadZipFile, subprocess.SubprocessError) as error:
        parser.exit(1, 'Shell audit failed: ' + str(error) + '\n')
    print(f'Shell audit passed: {count} required own libraries; no disallowed runtime entries')


if __name__ == '__main__':
    main()
