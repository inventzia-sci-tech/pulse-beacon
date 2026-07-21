#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
# Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
#
# Cross-package distribution smoke test (PackagingAndPublishingRelease.md §7).
#
# Tests the BUILT ARTIFACTS, not the source tree — the guarantee that an
# independent user can `pip install pulse-data pulse-beacon` into a clean
# environment. Run from the `New/` directory that holds both repos:
#
#   conda run -n pulse bash dist-smoke-test.sh
#
# Checks:
#   1. Both wheels build.
#   2. Both install into ONE clean venv and both import  (PEP 420 namespace guard).
#   3. Codec roundtrip works from the installed packages (not the repo).
#   4. Regeneration stays packaging-safe: zero diff + public-namespace imports.
#   5. Per-component version alignment: Java pom base version == Python version.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"          # pulse-beacon repo root
BEACON="$HERE"
DATA="$(cd "${PULSE_DATA_DIR:-$HERE/../pulse-data}" && pwd)"  # sibling pulse-data (override: PULSE_DATA_DIR)
PY="${PYTHON:-python}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
pass() { echo "  ✅ $1"; }
fail() { echo "  ❌ $1"; exit 1; }

echo "== 1. Build both wheels =="
# These checks validate the Python packaging (layout, namespaces, imports, fixtures),
# not the runtime-jar bundling — so allow a jar-less beacon wheel here. The jar
# requirement is enforced separately by the backend guard, exercised in check 1c.
export PULSE_ALLOW_NO_RUNTIME_JAR=1
( cd "$DATA"   && "$PY" -m build --wheel -o "$WORK/dist" >/dev/null ) && pass "pulse-data wheel"
( cd "$BEACON" && "$PY" -m build --wheel -o "$WORK/dist" >/dev/null ) && pass "pulse-beacon wheel"

echo "== 1b. Example fixtures ship in the beacon wheel and match the Java source =="
BEACON_WHL="$(ls "$WORK"/dist/pulse_beacon-*.whl)"
"$PY" - "$BEACON_WHL" <<'PY' && pass "fixtures bundled as package data"
import sys, zipfile
jsonl = [n for n in zipfile.ZipFile(sys.argv[1]).namelist()
         if n.endswith(".jsonl") and "/examples/_data/" in n]
assert jsonl, "no example fixtures under examples/_data/ in the wheel"
PY
if diff -rq "$BEACON/src/inventzia/pulse/beacon/core/examples/_data" \
        "$BEACON/core/java/src/main/resources/examples/data" >/dev/null 2>&1; then
    pass "packaged fixtures match the Java example resources (no drift)"
else
    diff -rq "$BEACON/src/inventzia/pulse/beacon/core/examples/_data" \
        "$BEACON/core/java/src/main/resources/examples/data" || true
    fail "packaged example fixtures drifted from the Java resources"
fi

echo "== 1c. Beacon wheel build refuses to silently omit the runtime jar =="
# Temporarily hide the jar (if a dev build staged it) and build without the opt-out;
# the in-tree backend must fail the build rather than ship an incomplete JPype wheel.
JAR="$BEACON/src/inventzia/pulse/beacon/_runtime/pulse-beacon-runtime.jar"
STASH="$WORK/stashed-runtime.jar"; MOVED=0
[ -f "$JAR" ] && { mv "$JAR" "$STASH"; MOVED=1; }
if ( cd "$BEACON" && env -u PULSE_ALLOW_NO_RUNTIME_JAR "$PY" -m build --wheel -o "$WORK/nojar" ) >/dev/null 2>&1; then
    [ "$MOVED" = 1 ] && mv "$STASH" "$JAR"
    fail "beacon wheel built WITHOUT the runtime jar — guard not enforced"
else
    [ "$MOVED" = 1 ] && mv "$STASH" "$JAR"
    pass "beacon wheel build fails when the runtime jar is absent (guard enforced)"
fi

echo "== 2. Both wheels install into one clean venv and both import (namespace guard) =="
"$PY" -m venv "$WORK/venv"
"$WORK/venv/bin/pip" install -q "$WORK"/dist/pulse_data-*.whl "$WORK"/dist/pulse_beacon-*.whl
# Run from a neutral cwd so nothing resolves off the source tree.
( cd "$WORK" && "$WORK/venv/bin/python" - <<'PY' ) && pass "both namespaces + documented public imports resolve"
import inventzia.pulse.data, inventzia.pulse.beacon           # both distributions, one namespace
from inventzia.pulse.beacon.core import BeaconActor           # documented public import (§Objective)
from inventzia.pulse.data.datum import Datum                  # documented public import
assert BeaconActor and Datum
PY

echo "== 3. Codec roundtrip from installed packages =="
( cd "$WORK" && "$WORK/venv/bin/python" - <<'PY' ) && pass "tagged-JSON codec roundtrip"
from inventzia.pulse.data.datum.codec import to_tagged_json, from_tagged_json
from inventzia.pulse.data.schemas.platform.heart_beat import HeartBeat
hb = HeartBeat(beatKey="BEAT", beatTime=1000)
assert from_tagged_json(to_tagged_json(hb)) == hb, "roundtrip mismatch"
PY

echo "== 4. Regeneration is packaging-safe (zero diff + public imports) =="
"$PY" "$DATA/schemas/schemas-generators/generate_python.py" \
    --schemas-dir "$DATA/schemas/schemas_yaml" --output-dir "$WORK/regen" \
    --base-package inventzia.pulse.data.schemas >/dev/null
# The generator owns exactly the schemas/ subtree (PEP 420, no __init__.py); the
# hand-written datum/ and package __init__.py files above it are not regenerated.
REGEN_SCHEMAS="$WORK/regen/inventzia/pulse/data/schemas"
SRC_SCHEMAS="$DATA/src/inventzia/pulse/data/schemas"
if diff -r -x '__pycache__' "$REGEN_SCHEMAS" "$SRC_SCHEMAS" >/dev/null 2>&1; then
    pass "regenerated schemas match committed output (zero diff)"
else
    diff -r -x '__pycache__' "$REGEN_SCHEMAS" "$SRC_SCHEMAS" || true
    fail "regeneration drifted from committed output"
fi
if grep -rqE "from (datum\.python|schemas\.schemas_py|core\.python)\." "$WORK/regen"; then
    fail "generator emitted repo-root-prefixed imports (must be public inventzia.*)"
fi
pass "generated imports use the public namespace"

echo "== 5. Per-component version consistency (dev mode) =="
# Delegate to the shared checker (base versions align, Maven -SNAPSHOT allowed).
# Release CI additionally runs `check-versions.sh --release` (no SNAPSHOT, exact).
bash "$HERE/check-versions.sh" | sed 's/^/  /'
[ "${PIPESTATUS[0]}" -eq 0 ] && pass "version consistency (dev)" || fail "version inconsistency"

echo ""
echo "ALL DISTRIBUTION CHECKS PASSED"
