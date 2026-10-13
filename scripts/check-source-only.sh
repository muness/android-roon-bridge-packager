#!/bin/sh
set -eu
exec python3 "$(dirname "$0")/check-source-only.py"
