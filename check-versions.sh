#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
# Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
#
# Version-consistency check across the Python and Java halves of each component
# (PackagingAndPublishingRelease.md §5-§6).
#
#   dev mode (default): base versions align per component; Maven -SNAPSHOT allowed.
#   release mode (--release or PULSE_RELEASE=1): Maven versions must be FINAL (no
#     -SNAPSHOT) and exactly equal their Python counterpart; the pulse-data version
#     embedded in the beacon runtime jar must equal Python pulse-data; and the
#     pulse-beacon wheel must pin pulse-data== that exact version (the runtime jar
#     embeds Java pulse-data, so the installed Python pulse-data must match, not just
#     be <0.2).
#
#   bash check-versions.sh [--release] [--tag vX.Y.Z]
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"          # pulse-beacon repo root
BEACON="$HERE"
DATA="$(cd "${PULSE_DATA_DIR:-$HERE/../pulse-data}" && pwd)"  # sibling pulse-data (override: PULSE_DATA_DIR)
RELEASE="${PULSE_RELEASE:-}"
TAG=""
while [ "$#" -gt 0 ]; do
    case "$1" in
        --release) RELEASE=1; shift ;;
        --tag)
            [ "$#" -ge 2 ] || { echo "--tag requires a value" >&2; exit 2; }
            TAG="$2"; shift 2 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

fail() { echo "  ❌ $1" >&2; exit 1; }
ok()   { echo "  ✅ $1"; }

pom_version() { grep -m1 -oE '<version>[^<]+</version>' "$1" | sed -E 's/<[^>]+>//g'; }
py_version()  { grep -m1 -oE '^version = "[^"]+"' "$1" | sed -E 's/version = "//; s/"//'; }
pom_prop()    { grep -m1 -oE "<$2>[^<]+</$2>" "$1" | sed -E 's/<[^>]+>//g'; }
base()        { echo "${1%-SNAPSHOT}"; }

DATA_PY=$(py_version  "$DATA/pyproject.toml")
DATA_POM=$(pom_version "$DATA/pom.xml")
BEACON_PY=$(py_version  "$BEACON/pyproject.toml")
BEACON_POM=$(pom_version "$BEACON/core/java/pom.xml")
JAR_DATA=$(pom_prop "$BEACON/core/java/pom.xml" "pulse-data.version")   # pulse-data embedded in the runtime jar
PY_DATADEP=$(grep -m1 -oE 'pulse-data(==|~=|>=|<=|<|>)[^"]*' "$BEACON/pyproject.toml" || true)

echo "pulse-data : python=$DATA_PY  maven=$DATA_POM"
echo "pulse-beacon: python=$BEACON_PY  maven=$BEACON_POM  (jar embeds pulse-data=$JAR_DATA; wheel pins '$PY_DATADEP')"

if [ -n "$RELEASE" ]; then
    echo "== release mode: no -SNAPSHOT, exact Python==Maven, pinned pulse-data =="
    for v in "$DATA_POM" "$BEACON_POM" "$JAR_DATA"; do
        case "$v" in
            *-SNAPSHOT) fail "release build carries a SNAPSHOT Maven version ($v). Flip the pom versions to the final release version first (see §5) — otherwise the stable wheel embeds a snapshot Java runtime.";;
        esac
    done
    [ "$DATA_POM"  = "$DATA_PY" ]   || fail "pulse-data version mismatch: maven $DATA_POM != python $DATA_PY"
    [ "$BEACON_POM" = "$BEACON_PY" ] || fail "pulse-beacon version mismatch: maven $BEACON_POM != python $BEACON_PY"
    [ "$JAR_DATA"  = "$DATA_PY" ]   || fail "runtime jar embeds pulse-data $JAR_DATA, but Python pulse-data is $DATA_PY — they must match"
    [ "$PY_DATADEP" = "pulse-data==$DATA_PY" ] || fail "pulse-beacon must pin 'pulse-data==$DATA_PY' for release (found '$PY_DATADEP'); the jar embeds an exact pulse-data, so <0.2 is too loose"
    if [ -n "$TAG" ]; then
        [ "$TAG" = "v$BEACON_PY" ] || fail "release tag $TAG does not match package version v$BEACON_PY"
    fi
    ok "release versions consistent: pulse-data=$DATA_PY, pulse-beacon=$BEACON_PY (final, exact, jar+wheel pulse-data pinned)"
else
    echo "== dev mode: base versions align (Maven -SNAPSHOT allowed) =="
    [ "$(base "$DATA_POM")"  = "$DATA_PY" ]   || fail "pulse-data: maven base $(base "$DATA_POM") != python $DATA_PY"
    [ "$(base "$BEACON_POM")" = "$BEACON_PY" ] || fail "pulse-beacon: maven base $(base "$BEACON_POM") != python $BEACON_PY"
    [ "$(base "$JAR_DATA")"  = "$DATA_PY" ]   || fail "runtime jar's pulse-data base $(base "$JAR_DATA") != python pulse-data $DATA_PY"
    ok "dev versions aligned (base): pulse-data=$DATA_PY, pulse-beacon=$BEACON_PY"
fi
