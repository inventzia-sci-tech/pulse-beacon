#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
# Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
#
# Run a Java-host (JEP) cross-language example manually. Pass a simple example name
# (default: HistoricRunJepExample; e.g. RealTimeRunJepExample) or a fully-qualified
# main class. The automated equivalent of the historic run is
# tests/test_historic_run_jep.py.
#
# Prereqs: the `pulse` conda env active (openjdk + maven + jep), and the module
# built (`mvn -o package -DskipTests` + staged jars, or at least `mvn -o compile`).
# Run from core/java/:  conda run -n pulse ./run-jep-example.sh [ExampleName]
set -euo pipefail

: "${CONDA_PREFIX:?activate the pulse env first: conda activate pulse}"

# Main class: bare name is resolved under the examples package; or pass an FQN.
MAIN="${1:-HistoricRunJepExample}"
case "$MAIN" in
  *.*) : ;;  # already fully qualified
  *)   MAIN="com.inventzia.pulse.beacon.core.examples.$MAIN" ;;
esac

# JEP artifacts (version-agnostic): the native lib's dir + the jep jar next to it.
LIBJEP="$(find "$CONDA_PREFIX/lib" -name libjep.so 2>/dev/null | head -1)"
[ -n "$LIBJEP" ] || { echo "jep not installed in this env (no libjep.so)"; exit 1; }
JEP_DIR="$(dirname "$LIBJEP")"
JEP_JAR="$(ls "$JEP_DIR"/jep-*.jar | head -1)"

# .../New (holds pulse-beacon + pulse-data, the two Python import roots).
REPO_ROOT="$(cd ../../.. && pwd)"

# Runtime classpath: fresh classes + module runtime deps + the jep jar.
mvn -o -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt
CP="target/classes:$(cat target/cp.txt):$JEP_JAR"

export LD_LIBRARY_PATH="$CONDA_PREFIX/lib:$JEP_DIR:${LD_LIBRARY_PATH:-}"
exec java -cp "$CP" \
    -Djava.library.path="$JEP_DIR" \
    -Dpulse.repo.root="$REPO_ROOT" \
    "$MAIN"
