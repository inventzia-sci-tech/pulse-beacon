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
#   conda run -n pulse bash release-build.sh [OUTPUT_DIR]
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"          # pulse-beacon repo root
BEACON="$HERE"
DATA="$(cd "${PULSE_DATA_DIR:-$HERE/../pulse-data}" && pwd)"  # sibling pulse-data (override: PULSE_DATA_DIR)
OUT="${1:-$HERE/dist}"
PY="${PYTHON:-python}"
JAR="$BEACON/src/inventzia/pulse/beacon/_runtime/pulse-beacon-runtime.jar"

echo "==> version consistency (release mode)"
# Rejects a release that would embed a snapshot / mismatched Java runtime. If this
# fails on SNAPSHOT, flip the pom versions to the final release version first.
bash "$BEACON/check-versions.sh" --release

echo "==> pulse-data wheel"
( cd "$DATA" && "$PY" -m build --wheel -o "$OUT" )

echo "==> pulse-beacon runtime jar"
( cd "$BEACON" && ./build-runtime-jar.sh )
test -f "$JAR" || { echo "FATAL: runtime jar not produced at $JAR" >&2; exit 1; }

echo "==> pulse-beacon wheel (backend enforces the bundled jar)"
( cd "$BEACON" && "$PY" -m build --wheel -o "$OUT" )

echo ""
echo "Release wheels in $OUT:"
ls -1 "$OUT"/*.whl
