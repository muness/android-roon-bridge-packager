#!/usr/bin/env python3
"""Explicit maintainer qualification, separate from builds and CI.

Default: offline catalogue checks. --check-supplements downloads free Debian
inputs. --qualify-vendor explicitly downloads the official vendor archive only
into an outside-repository cache; it never executes or republishes vendor code.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import tarfile
import tempfile
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
CATALOGUE = ROOT / 'android/app/src/main/assets/catalogue.json'
SHELL_GLIBC_VERSION = '2.41-12+deb13u4'


class Invalid(ValueError):
    pass


def check_catalogue(catalogue):
    rows = catalogue.get('endpoints', [])
    if len(rows) != 1 or rows[0].get('id') != 'roon':
        raise Invalid('exactly one Roon endpoint required')
    endpoint = rows[0]
    archive = endpoint['vendorArchive']
    if archive['urlPattern'] != 'https://download.roonlabs.com/builds/RoonBridge_linuxarmv8.tar.bz2' or archive.get('pinOnFirstUse'):
        raise Invalid('official pinned ARMv8 vendor source required')
    if not re.fullmatch(r'[0-9a-f]{64}', archive['sha256ByVersion'].get(endpoint['vendorVersion'], '')):
        raise Invalid('vendor SHA256 pin required')
    supplements = endpoint['packPlan']['supplements']
    if len(supplements) != 4:
        raise Invalid('four Debian supplements required')
    for supplement in supplements:
        if not re.fullmatch(r'https://snapshot\.debian\.org/file/[0-9a-f]{40}', supplement['url']):
            raise Invalid('content-addressed official Debian Snapshot URL required')
        if supplement['format'] != 'deb' or not supplement['required'] or not re.fullmatch(r'[0-9a-f]{64}', supplement['sha256']):
            raise Invalid('required pinned Debian input expected')
    return endpoint


class Redirects(urllib.request.HTTPRedirectHandler):
    def __init__(self, hosts):
        super().__init__()
        self.hosts = hosts

    def redirect_request(self, request, response, code, message, headers, newurl):
        parsed = urllib.parse.urlsplit(newurl)
        if parsed.scheme != 'https' or parsed.hostname not in self.hosts or parsed.username or parsed.password:
            raise Invalid('download redirected outside its official HTTPS hosts')
        return super().redirect_request(request, response, code, message, headers, newurl)


def download(url, expected, cache, hosts, max_bytes):
    cache = Path(cache).resolve()
    if cache == ROOT or ROOT in cache.parents:
        raise Invalid('qualification cache must be outside the source repository')
    cache.mkdir(parents=True, exist_ok=True)
    target = cache / expected
    if target.exists():
        if target.stat().st_size > max_bytes or hashlib.sha256(target.read_bytes()).hexdigest() != expected:
            raise Invalid('cached input does not match its pin')
        return target
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != 'https' or parsed.hostname not in hosts or parsed.username or parsed.password:
        raise Invalid('official HTTPS source required')
    opener = urllib.request.build_opener(Redirects(hosts))
    descriptor, partial_name = tempfile.mkstemp(prefix=expected + '.', suffix='.part', dir=cache)
    os.close(descriptor)
    partial = Path(partial_name)
    digest = hashlib.sha256()
    size = 0
    try:
        request = urllib.request.Request(url, headers={'User-Agent': 'RoonBridgePackager-Maintainer/1.0', 'Accept': '*/*'})
        with opener.open(request, timeout=60) as response, partial.open('wb') as output:
            while True:
                block = response.read(1024 * 1024)
                if not block:
                    break
                size += len(block)
                if size > max_bytes:
                    raise Invalid('input exceeds qualification size bound')
                digest.update(block)
                output.write(block)
        if digest.hexdigest() != expected:
            raise Invalid('download does not match its SHA256 pin')
        partial.replace(target)
    finally:
        partial.unlink(missing_ok=True)
    return target


def check_elf(data, soname=None):
    if len(data) < 64 or data[:6] != b'\x7fELF\x02\x01' or struct.unpack_from('<H', data, 18)[0] != 183:
        raise Invalid('input member must be little-endian ELF64 AArch64')
    if soname is None:
        return
    offset = struct.unpack_from('<Q', data, 40)[0]
    stride, count = struct.unpack_from('<HH', data, 58)
    if stride < 64 or offset + count * stride > len(data):
        raise Invalid('invalid ELF section table')
    for index in range(count):
        at = offset + stride * index
        if struct.unpack_from('<I', data, at + 4)[0] != 6:
            continue
        dynamic, size, link = struct.unpack_from('<QQI', data, at + 24)
        if link >= count or dynamic + size > len(data):
            raise Invalid('invalid ELF dynamic section')
        string_at = offset + stride * link
        strings, string_size = struct.unpack_from('<QQ', data, string_at + 24)
        if strings + string_size > len(data):
            raise Invalid('invalid ELF string section')
        for pos in range(dynamic, dynamic + size - 15, 16):
            tag, value = struct.unpack_from('<QQ', data, pos)
            if tag == 14 and value < string_size:
                end = data.find(b'\0', strings + value, strings + string_size)
                if end >= 0 and data[strings + value:end] == soname.encode('ascii'):
                    return
    raise Invalid('input member SONAME does not match the catalogue')


def verify_deb(path, supplement, expected_glibc=SHELL_GLIBC_VERSION):
    control = subprocess.check_output(['dpkg-deb', '--field', str(path), 'Package', 'Version', 'Architecture'], text=True)
    fields = dict(line.split(': ', 1) for line in control.splitlines())
    if fields.get('Architecture') != 'arm64':
        raise Invalid('supplement package must be arm64')
    if fields.get('Package') == 'libc6' and fields.get('Version') != expected_glibc:
        raise Invalid('supplement glibc must match the shell glibc build')
    wanted = {member['path']: member for member in supplement['members']}
    found = set()
    process = subprocess.Popen(['dpkg-deb', '--fsys-tarfile', str(path)], stdout=subprocess.PIPE)
    try:
        with tarfile.open(fileobj=process.stdout, mode='r|') as archive:
            for member in archive:
                name = member.name.removeprefix('./')
                if name not in wanted:
                    continue
                if not member.isfile() or member.size > 32 * 1024 * 1024:
                    raise Invalid('supplement member must be a bounded regular file')
                check_elf(archive.extractfile(member).read(), wanted[name]['soname'])
                found.add(name)
        if process.wait() != 0:
            raise Invalid('invalid Debian data archive')
    finally:
        process.stdout.close()
        if process.poll() is None:
            process.terminate()
            process.wait()
    if found != set(wanted):
        raise Invalid('Debian supplement is missing a declared member')
    return {'package': fields['Package'], 'version': fields['Version'], 'architecture': fields['Architecture'], 'members': len(found)}


def verify_vendor(path, endpoint):
    versions = []
    hostfxr = False
    with tarfile.open(path, 'r:bz2') as archive:
        for member in archive:
            if member.name.endswith('/VERSION') or member.name == 'VERSION':
                if not member.isfile() or member.size > 4096:
                    raise Invalid('invalid vendor VERSION member')
                text = archive.extractfile(member).read().decode('ascii')
                match = re.search(r'^([0-9]+\.[0-9]+) \(build ([0-9]+)\) production$', text, re.MULTILINE)
                if not match:
                    raise Invalid('vendor production version not recognized')
                versions.append(match[1] + '-' + match[2])
            if Path(member.name).name == endpoint['packPlan']['hostfxrName'] and member.isfile():
                check_elf(archive.extractfile(member).read(64))
                hostfxr = True
    if not versions or set(versions) != {endpoint['vendorVersion']}:
        raise Invalid('vendor VERSION does not match the catalogue label')
    if not hostfxr:
        raise Invalid('official ARM64 archive is missing its hostfxr runtime')
    return {'version': endpoint['vendorVersion'], 'versionFiles': len(versions), 'arm64Hostfxr': True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--catalogue', type=Path, default=CATALOGUE)
    parser.add_argument('--cache-dir', type=Path)
    parser.add_argument('--check-supplements', action='store_true', help='explicitly GET, hash and inspect every Debian input')
    parser.add_argument('--qualify-vendor', action='store_true', help='explicit maintainer-only vendor download; never run in build/CI')
    parser.add_argument('--shell-glibc-version', default=SHELL_GLIBC_VERSION)
    args = parser.parse_args()
    endpoint = check_catalogue(json.loads(args.catalogue.read_text()))
    if (args.check_supplements or args.qualify_vendor) and args.cache_dir is None:
        parser.error('network qualification requires --cache-dir outside the repository')
    result = {'catalogue': 'valid', 'networkRequested': args.check_supplements or args.qualify_vendor}
    if args.check_supplements:
        result['supplements'] = []
        for item in endpoint['packPlan']['supplements']:
            path = download(item['url'], item['sha256'], args.cache_dir, {'snapshot.debian.org'}, 32 * 1024 * 1024)
            result['supplements'].append(verify_deb(path, item, args.shell_glibc_version))
    if args.qualify_vendor:
        vendor = endpoint['vendorArchive']
        path = download(vendor['urlPattern'], vendor['sha256ByVersion'][endpoint['vendorVersion']], args.cache_dir, {'download.roonlabs.com', 'download.roonlabs.net'}, 256 * 1024 * 1024)
        result['vendor'] = verify_vendor(path, endpoint)
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
