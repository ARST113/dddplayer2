#!/usr/bin/env bash
set -euo pipefail

BUILD="$(cd "$(dirname "$0")/.." && pwd)/.justplus-upstream/app/build.gradle"
test -f "$BUILD"

python3 - "$BUILD" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text()
s = s.replace(
    'versionCode (project.findProperty("versionCodeOverride") ?: "1500000").toString().toInteger()',
    'versionCode 1500000'
)
s = s.replace(
    'versionName (project.findProperty("versionNameOverride") ?: "0.0.15").toString()',
    'versionName "0.0.15"'
)
p.write_text(s)
PY
