#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
# Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
#
# This file is part of pulse-beacon.
#
# pulse-beacon is dual-licensed:
#   - Under the GNU Affero General Public License v3.0 or later (see LICENSE-AGPL-3.0).
#   - Under a commercial license (see LICENSE-COMMERCIAL.txt).
#     Contact operations@inventzia.com.
#
# Build the shaded Beacon runtime jar and stage it into the Python package as data,
# so the wheel bundles it and JPype starts with no external jars / PYTHONPATH / Maven.
#
# Run before `python -m build` (the wheel build is not pure-Python — see
# PackagingAndPublishingRelease.md §3). Requires the pulse-data jar in the local
# .m2 (`cd pulse-data && mvn install -DskipTests`).
#
#   ./build-runtime-jar.sh
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAVA_DIR="$HERE/core/java"
DEST_DIR="$HERE/src/inventzia/pulse/beacon/_runtime"
DEST_JAR="$DEST_DIR/pulse-beacon-runtime.jar"

echo "==> Building shaded runtime jar (mvn -Pruntime-jar) ..."
mvn -q -f "$JAVA_DIR/pom.xml" -Pruntime-jar clean package -DskipTests

SHADED="$(ls "$JAVA_DIR"/target/pulse-beacon-core-*-runtime.jar | head -1)"
if [[ ! -f "$SHADED" ]]; then
    echo "ERROR: shaded runtime jar not found under $JAVA_DIR/target" >&2
    exit 1
fi

mkdir -p "$DEST_DIR"
cp "$SHADED" "$DEST_JAR"
echo "==> Staged $(basename "$SHADED") -> $DEST_JAR ($(du -h "$DEST_JAR" | cut -f1))"
