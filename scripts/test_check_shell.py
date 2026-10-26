"""Synthetic shell audit regressions; no vendor runtime or SDK download."""
import importlib.util
import json
from pathlib import Path
import struct
import tempfile
import unittest
import warnings
import zipfile


spec = importlib.util.spec_from_file_location('check_shell', Path(__file__).with_name('check-shell.py'))
check_shell = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check_shell)


class ShellAuditTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.catalogue = self.root / 'catalogue.json'
        self.catalogue.write_text(json.dumps({'endpoints': [{'id': 'roon', 'packPlan': {
            'nativeDir': 'lib/arm64-v8a/', 'ownLibraries': ['libfixture.so'],
            'treeAsset': 'assets/roon-tree.zip', 'mapAsset': 'assets/roon-exec-map.txt'}}]}))
        header = bytearray(64)
        header[:6] = b'\x7fELF\x02\x01'
        struct.pack_into('<H', header, 18, 183)
        self.entries = [('AndroidManifest.xml', b'synthetic manifest'),
                        ('classes.dex', b'dex\n035\0synthetic'),
                        ('lib/arm64-v8a/libfixture.so', bytes(header))]

    def shell(self, entries=None):
        path = self.root / 'shell.apk'
        with warnings.catch_warnings():
            warnings.simplefilter('ignore', UserWarning)
            with zipfile.ZipFile(path, 'w') as archive:
                for name, data in self.entries if entries is None else entries:
                    archive.writestr(name, data)
        return path

    def test_complete_own_library_shell_passes(self):
        self.assertEqual(1, check_shell.audit(self.shell(), self.catalogue))

    def test_missing_own_library_fails(self):
        with self.assertRaisesRegex(ValueError, 'missing required shell libraries'):
            check_shell.audit(self.shell(self.entries[:2]), self.catalogue)

    def test_duplicate_native_entry_fails(self):
        with self.assertRaisesRegex(ValueError, 'duplicate ZIP entry'):
            check_shell.audit(self.shell(self.entries + [self.entries[-1]]), self.catalogue)

    def test_vendor_tree_map_and_unknown_native_fail(self):
        for name in ('assets/roon-tree.zip', 'assets/roon-exec-map.txt',
                     'lib/arm64-v8a/libhostfxr.so', 'lib/arm64-v8a/libunknown.so'):
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, 'vendor|outside catalogue'):
                check_shell.audit(self.shell(self.entries + [(name, b'synthetic')]), self.catalogue)

    def test_disguised_native_payload_fails(self):
        with self.assertRaisesRegex(ValueError, 'payload outside ownLibraries'):
            check_shell.audit(self.shell(self.entries + [('assets/data', self.entries[-1][1])]),
                              self.catalogue)

    def test_invalid_own_library_architecture_fails(self):
        entries = self.entries[:2] + [(self.entries[-1][0], b'not an ELF library')]
        with self.assertRaisesRegex(ValueError, 'ELF64 AArch64'):
            check_shell.audit(self.shell(entries), self.catalogue)

    def child_shell(self, content):
        catalogue = json.loads(self.catalogue.read_text())
        catalogue['endpoints'][0]['packPlan']['ownLibraries'].append('libroonchild_exec.so')
        self.catalogue.write_text(json.dumps(catalogue))
        return self.shell(self.entries + [('lib/arm64-v8a/libroonchild_exec.so', content)])

    def test_exact_child_launcher_accepts_bounded_android_shell_script(self):
        self.assertEqual(2, check_shell.audit(
            self.child_shell(b'#!/system/bin/sh\nexec "$ROONBRIDGE_HOST" "$@"\n'), self.catalogue))

    def test_child_launcher_rejects_binary_missing_shebang_and_unbounded_content(self):
        for content in (self.entries[-1][1], b'echo missing header\n',
                        b'#!/system/bin/sh\necho nul\0\n',
                        b'#!/system/bin/sh\n\xff',
                        b'#!/system/bin/sh\n' + b'x' * (64 * 1024)):
            with self.subTest(content_type=content[:20]):
                # Recreate the original catalogue for each synthetic variant.
                catalogue = json.loads(self.catalogue.read_text())
                catalogue['endpoints'][0]['packPlan']['ownLibraries'] = ['libfixture.so']
                self.catalogue.write_text(json.dumps(catalogue))
                with self.assertRaisesRegex(ValueError, 'child launcher'):
                    check_shell.audit(self.child_shell(content), self.catalogue)

    def test_unsafe_zip_paths_fail(self):
        for name in ('../outside', '/absolute', 'assets/../outside', 'assets//double', 'a\\b'):
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, 'unsafe ZIP path'):
                check_shell.audit(self.shell(self.entries + [(name, b'fixture')]), self.catalogue)

    def test_prepended_bytes_fail_zip_magic(self):
        path = self.shell()
        path.write_bytes(b'notzip' + path.read_bytes())
        with self.assertRaisesRegex(ValueError, 'ZIP local file header'):
            check_shell.audit(path, self.catalogue)

    def test_manifest_url_is_bound_to_same_metadata_element(self):
        dump = '''E: manifest
  E: application
    E: meta-data
      A: android:name(0x01010003)="io.github.muness.playback.SERVICE_URL"
      A: android:value(0x01010024)="https://playback.example.test"
    E: meta-data
      A: android:name(0x01010003)="unrelated"
      A: android:value(0x01010024)="https://other.example.test"
'''
        self.assertEqual('https://playback.example.test', check_shell.manifest_service_url(dump))
        with self.assertRaisesRegex(ValueError, 'exactly one literal'):
            check_shell.manifest_service_url(dump.replace(
                'A: android:value(0x01010024)="https://playback.example.test"',
                'A: android:value(0x01010024)=@0x7f001000'))
        with self.assertRaisesRegex(ValueError, 'exactly one literal'):
            check_shell.manifest_service_url(dump + dump)


if __name__ == '__main__':
    unittest.main()
