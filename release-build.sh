#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
# Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
#
# Build release wheels for both packages, with the pulse-beacon runtime jar bundled.
#
# The pulse-beacon wheel must carry the shaded runtime jar or it is a silently
# incomplete JPype distribution (PackagingAndPublishingRelease.md §3). This script
# builds that jar first and asserts it exists; the in-tree build backend
# (pulse-beacon/_build_backend.py) independently fails the wheel build if it is
# missing, so a release wheel cannot omit it whether built here or by hand.
#
# Two guards keep a stale build tree from contaminating a wheel: every package's
# build/ is removed before building, and every built wheel is checked so that each
# packaged module also exists in that package's src/ (setuptools' build_py copies
# current source into build/lib but never removes files that left the source, and
# bdist_wheel would then zip the leftover — this is how an obsolete schemas/registry.py
# once shipped).
#
#   conda run -n pulse bash release-build.sh [OUTPUT_DIR]
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"          # pulse-beacon repo root
BEACON="$HERE"
DATA="$(cd "${PULSE_DATA_DIR:-$HERE/../pulse-data}" && pwd)"  # sibling pulse-data (override: PULSE_DATA_DIR)
OUT="${1:-$HERE/dist}"
PY="${PYTHON:-python}"
JAR="$BEACON/src/inventzia/pulse/beacon/_runtime/pulse-beacon-runtime.jar"

# Fail if a wheel carries a package .py that is not in the package's src/ tree
# (i.e. a stale build/ leftover). The bundled runtime jar and data files are not .py,
# so they are unaffected; every generated .py is committed under src/, so it passes.
verify_wheel() {   # <wheel> <repo_dir>
  "$PY" - "$1" "$2" <<'PYEOF'
import os, sys, zipfile
whl, repo = sys.argv[1], sys.argv[2]
extra = [n for n in zipfile.ZipFile(whl).namelist()
         if n.startswith("inventzia/") and n.endswith(".py")
         and not os.path.isfile(os.path.join(repo, "src", n))]
if extra:
    sys.stderr.write("FATAL: wheel contains modules absent from src/ (stale build/ contamination):\n")
    sys.stderr.write("".join("  " + e + "\n" for e in extra))
    sys.exit(1)
print("  wheel content verified against src/: " + os.path.basename(whl))
PYEOF
}

echo "==> version consistency (release mode)"
# Rejects a release that would embed a snapshot / mismatched Java runtime. If this
# fails on SNAPSHOT, flip the pom versions to the final release version first.
bash "$BEACON/check-versions.sh" --release

echo "==> clean stale build artifacts (both packages)"
rm -rf "$DATA/build" "$DATA"/src/*.egg-info "$BEACON/build" "$BEACON"/src/*.egg-info

echo "==> pulse-data wheel"
( cd "$DATA" && "$PY" -m build --wheel -o "$OUT" )
verify_wheel "$(ls -t "$OUT"/pulse_data-*.whl | head -1)" "$DATA"

echo "==> pulse-data jar -> local .m2 (clean, so the shaded runtime jar embeds a current descriptor)"
# 'mvn install' without clean keeps a stale target/classes/META-INF/maven/.../pom.properties, so
# the shaded jar would bake an old pulse-data version (0.2.2 shipped a 0.2.0-SNAPSHOT descriptor).
( cd "$DATA" && mvn -q clean install -DskipTests )

echo "==> pulse-beacon runtime jar (its build asserts the embedded pulse-data descriptor)"
( cd "$BEACON" && ./build-runtime-jar.sh )
test -f "$JAR" || { echo "FATAL: runtime jar not produced at $JAR" >&2; exit 1; }

echo "==> pulse-beacon wheel (backend enforces the bundled jar)"
( cd "$BEACON" && "$PY" -m build --wheel -o "$OUT" )
verify_wheel "$(ls -t "$OUT"/pulse_beacon-*.whl | head -1)" "$BEACON"

echo ""
echo "Release wheels in $OUT:"
ls -1 "$OUT"/*.whl
