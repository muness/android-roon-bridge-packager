#!/usr/bin/env python3
"""Synthetic archive tests only: no vendor or network downloads."""
import hashlib
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import struct
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import urllib.request

spec = importlib.util.spec_from_file_location('preflight', Path(__file__).with_name('preflight-downloads.py'))
preflight = importlib.util.module_from_spec(spec)
spec.loader.exec_module(preflight)


def elf(soname='libdl.so.2', machine=183):
    strings = b'\0' + soname.encode() + b'\0'
    dynamic = 256 + len(strings)
    data = bytearray(dynamic + 32)
    data[:6] = b'\x7fELF\x02\x01'
    struct.pack_into('<H', data, 18, machine)
    struct.pack_into('<Q', data, 40, 64)
    struct.pack_into('<HH', data, 58, 64, 3)
    struct.pack_into('<I', data, 64 + 64 + 4, 3)
    struct.pack_into('<QQ', data, 64 + 64 + 24, 256, len(strings))
    struct.pack_into('<I', data, 64 + 128 + 4, 6)
    struct.pack_into('<QQI', data, 64 + 128 + 24, dynamic, 32, 1)
    data[256:dynamic] = strings
    struct.pack_into('<QQ', data, dynamic, 14, 1)
    return bytes(data)


def tar_bytes(files, mode='w:gz'):
    stream = io.BytesIO()
    with tarfile.open(fileobj=stream, mode=mode) as archive:
        for name, data in files.items():
            info = tarfile.TarInfo(name)
            info.size = len(data)
            archive.addfile(info, io.BytesIO(data))
    return stream.getvalue()


def deb(path, version='2.41-12+deb13u4', data=None, architecture='arm64'):
    control = f'Package: libc6\nVersion: {version}\nArchitecture: {architecture}\nMaintainer: Synthetic <test@example.invalid>\nDescription: synthetic fixture\n'
    members = [('debian-binary', b'2.0\n'), ('control.tar.gz', tar_bytes({'./control': control.encode()})), ('data.tar.gz', tar_bytes(data or {'./usr/lib/libdl.so.2': elf()}))]
    content = b'!<arch>\n'
    for name, value in members:
        content += f'{name + "/":<16}{0:<12}{0:<6}{0:<6}{"100644":<8}{len(value):<10}`\n'.encode() + value
        if len(value) % 2:
            content += b'\n'
    path.write_bytes(content)
    return path


class PreflightTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.catalogue = json.loads(preflight.CATALOGUE.read_text())
        self.endpoint = self.catalogue['endpoints'][0]
        self.supplement = {'members': [{'path': 'usr/lib/libdl.so.2', 'soname': 'libdl.so.2'}]}

    def test_shipped_inputs_use_content_addresses(self):
        preflight.check_catalogue(self.catalogue)

    def test_rotating_mirror_input_is_rejected_offline(self):
        self.endpoint['packPlan']['supplements'][0]['url'] = 'https://deb.debian.org/debian/pool/example.deb'
        with self.assertRaisesRegex(preflight.Invalid, 'content-addressed'):
            preflight.check_catalogue(self.catalogue)

    def test_incomplete_input_set_is_rejected(self):
        self.endpoint['packPlan']['supplements'].pop()
        with self.assertRaisesRegex(preflight.Invalid, 'four Debian'):
            preflight.check_catalogue(self.catalogue)

    def test_declared_elf_soname_and_architecture_are_checked(self):
        preflight.check_elf(elf(), 'libdl.so.2')
        for data, soname in [(elf(machine=62), 'libdl.so.2'), (elf(), 'libpthread.so.0'), (b'not ELF', 'libdl.so.2')]:
            with self.assertRaises(preflight.Invalid):
                preflight.check_elf(data, soname)

    def test_debian_control_and_declared_member_are_checked(self):
        result = preflight.verify_deb(deb(self.root / 'ok.deb'), self.supplement)
        self.assertEqual('2.41-12+deb13u4', result['version'])
        self.assertEqual(1, result['members'])

    def test_old_glibc_cannot_satisfy_new_shell(self):
        with self.assertRaisesRegex(preflight.Invalid, 'match the shell'):
            preflight.verify_deb(deb(self.root / 'old.deb', version='2.41-12+deb13u3'), self.supplement)

    def test_wrong_package_architecture_and_missing_member_are_rejected(self):
        with self.assertRaisesRegex(preflight.Invalid, 'arm64'):
            preflight.verify_deb(deb(self.root / 'x64.deb', architecture='amd64'), self.supplement)
        with self.assertRaisesRegex(preflight.Invalid, 'missing a declared member'):
            preflight.verify_deb(deb(self.root / 'missing.deb', data={'./elsewhere': elf()}), self.supplement)

    def test_actual_vendor_version_cannot_be_relabelled(self):
        data = tar_bytes({'RoonBridge/VERSION': b'207101683\n2.71 (build 1683) production\nproduction', 'RoonBridge/libhostfxr.so': elf()}, 'w:bz2')
        path = self.root / 'synthetic.tar.bz2'
        path.write_bytes(data)
        self.assertEqual('2.71-1683', preflight.verify_vendor(path, self.endpoint)['version'])
        self.endpoint['vendorVersion'] = '2.74-1712'
        with self.assertRaisesRegex(preflight.Invalid, 'does not match'):
            preflight.verify_vendor(path, self.endpoint)

    def test_conflicting_version_files_are_rejected(self):
        path = self.root / 'synthetic.tar.bz2'
        path.write_bytes(tar_bytes({'RoonBridge/VERSION': b'2.71 (build 1683) production\n', 'RoonBridge/Bridge/VERSION': b'2.74 (build 1712) production\n', 'RoonBridge/libhostfxr.so': elf()}, 'w:bz2'))
        with self.assertRaisesRegex(preflight.Invalid, 'does not match'):
            preflight.verify_vendor(path, self.endpoint)

    def test_vendor_bytes_never_download_by_default(self):
        output = io.StringIO()
        with patch('sys.argv', ['preflight-downloads.py']), patch('urllib.request.build_opener', side_effect=AssertionError('network must not be used')), contextlib.redirect_stdout(output):
            preflight.main()
        self.assertFalse(json.loads(output.getvalue())['networkRequested'])

    def test_outside_repo_cache_enforced_and_cached_hash_rechecked(self):
        sha = hashlib.sha256(b'fixture').hexdigest()
        with self.assertRaisesRegex(preflight.Invalid, 'outside'):
            preflight.download('https://snapshot.debian.org/file/' + '0' * 40, sha, preflight.ROOT / 'local', {'snapshot.debian.org'}, 1024)
        (self.root / sha).write_bytes(b'changed')
        with self.assertRaisesRegex(preflight.Invalid, 'cached input'):
            preflight.download('https://snapshot.debian.org/file/' + '0' * 40, sha, self.root, {'snapshot.debian.org'}, 1024)

    def test_download_hash_failure_never_promotes_partial_bytes(self):
        expected = hashlib.sha256(b'expected').hexdigest()
        with patch('urllib.request.build_opener') as make:
            make.return_value.open.return_value = io.BytesIO(b'changed')
            with self.assertRaisesRegex(preflight.Invalid, 'SHA256'):
                preflight.download('https://snapshot.debian.org/file/' + '0' * 40, expected, self.root, {'snapshot.debian.org'}, 1024)
        self.assertEqual([], list(self.root.iterdir()))

    def test_download_body_is_bounded_without_content_length(self):
        expected = hashlib.sha256(b'abcd').hexdigest()
        with patch('urllib.request.build_opener') as make:
            make.return_value.open.return_value = io.BytesIO(b'abcd')
            with self.assertRaisesRegex(preflight.Invalid, 'size bound'):
                preflight.download('https://snapshot.debian.org/file/' + '0' * 40, expected, self.root, {'snapshot.debian.org'}, 3)
        self.assertEqual([], list(self.root.iterdir()))

    def test_insecure_or_external_redirect_is_rejected(self):
        request = urllib.request.Request('https://download.roonlabs.com/builds/example')
        handler = preflight.Redirects({'download.roonlabs.com', 'download.roonlabs.net'})
        for url in ['http://download.roonlabs.net/file', 'https://evil.example/file', 'https://user:pass@download.roonlabs.net/file']:
            with self.assertRaisesRegex(preflight.Invalid, 'outside'):
                handler.redirect_request(request, None, 302, '', {}, url)


if __name__ == '__main__':
    unittest.main()
