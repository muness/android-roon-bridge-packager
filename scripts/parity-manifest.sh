#!/bin/sh
set -eu

# One line per APK entry: <method> <sha256 of the entry's content> <name>,
# sorted. This is what a desktop packing run and an on-device packing run are
# compared on, and it is deliberately not a hash of the whole file:
#
#   - alignment padding lives in a local header's extra field, so it is not
#     content, and the two runs are allowed to pad differently;
#   - the signature lives in the APK signing block between the entries and the
#     central directory, and two signatures over the same content differ
#     because a signature contains a fresh random value;
#   - META-INF entries are a v1 signature, which neither packer produces.
#
# What has to match is the entry list, how each entry is stored, and the bytes
# of each entry's content. That is what this prints.
#
# Usage: scripts/parity-manifest.sh <apk> [<apk> ...]

[ "$#" -ge 1 ] || { echo "usage: $0 <apk> [<apk> ...]" >&2; exit 2; }

for apk in "$@"; do
    [ -f "$apk" ] || { echo "no such file: $apk" >&2; exit 1; }
    if [ "$#" -gt 1 ]; then printf '== %s\n' "$apk"; fi
    python3 - "$apk" <<'PY'
import hashlib, io, sys, zipfile

# An asset that is itself a zip -- the tree archive a tree-plan endpoint
# carries -- is a container, not a member. Two runs that put the same files in
# it produce different container bytes, because a deflate stream is an
# implementation's choice. So its members are listed instead, which is what
# actually has to match.
NESTED = ("-tree.zip",)

with zipfile.ZipFile(sys.argv[1]) as z:
    rows = []
    for info in z.infolist():
        if info.is_dir():
            continue
        if info.filename.startswith("META-INF/"):
            continue
        method = "STORED" if info.compress_type == zipfile.ZIP_STORED else "DEFLATED"
        content = z.read(info.filename)
        if info.filename.endswith(NESTED):
            with zipfile.ZipFile(io.BytesIO(content)) as inner:
                for member in inner.infolist():
                    if member.is_dir():
                        continue
                    rows.append((info.filename + "!" + member.filename, "TREE",
                                 hashlib.sha256(inner.read(member.filename)).hexdigest()))
            rows.append((info.filename, method + "(container)", "-" * 64))
            continue
        rows.append((info.filename, method, hashlib.sha256(content).hexdigest()))
    for name, method, digest in sorted(rows):
        print(method, digest, name)
PY
done
