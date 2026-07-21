# SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
# Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
#
# This file is part of pulse-beacon.
#
# pulse-beacon is dual-licensed:
#   - Under the GNU Affero General Public License v3.0 or later (see LICENSE-AGPL-3.0).
#   - Under a commercial license (see LICENSE-COMMERCIAL.txt).
#     Contact operations@inventzia.com.
"""
Python-host bootstrap: start an embedded JVM (via JPype) on the Beacon classpath.

This is the only direction-specific piece for the Python-host launcher. The
classpath is resolved in two ways, in order:

  1. **Installed package (zero-config):** a single shaded *runtime jar* — Beacon
     plus its ordinary Java dependencies — bundled inside the wheel at
     ``inventzia.pulse.beacon._runtime``. Located via ``importlib.resources``, so
     ``pip install pulse-beacon`` needs no staged jars, ``PYTHONPATH``, or Maven.
  2. **Source checkout (dev):** the Maven-produced release jar set staged into
     ``<pulse-beacon>/core/java/jars/`` (the module jar plus runtime deps).

JPype finds the JVM itself from ``JAVA_HOME`` — a jar is not a JVM, so a JDK/JRE
17+ must be present (see PackagingAndPublishingRelease.md §3). Everything above
the bootstrap (actors, gateways, channel, dispatch, streamer) is JVM-agnostic
and unchanged from the Java-host (JEP) path.
"""

import glob
import os
from pathlib import Path

from inventzia.pulse.beacon.core.reporter import ComponentReporter

_log = ComponentReporter("jpype-host")

# The wheel-bundled shaded runtime jar (built by core/java `mvn -Pruntime-jar`
# and copied here — see pulse-beacon/build-runtime-jar.sh).
_RUNTIME_PACKAGE = "inventzia.pulse.beacon._runtime"
_RUNTIME_JAR = "pulse-beacon-runtime.jar"


def bundled_runtime_jar() -> Path | None:
    """The shaded runtime jar bundled in the installed wheel, or ``None`` if absent.

    Present in an installed/built package; absent in a bare source checkout that
    hasn't run ``build-runtime-jar.sh`` (dev then falls back to the staged jars).
    """
    try:
        from importlib.resources import files
        res = files(_RUNTIME_PACKAGE).joinpath(_RUNTIME_JAR)
        path = Path(str(res))
        return path if path.is_file() else None
    except (ModuleNotFoundError, FileNotFoundError, TypeError):
        return None


def default_jars_dir() -> Path:
    """The staged release jars directory: ``<pulse-beacon>/core/java/jars``.

    Source-checkout convenience only; for an installed package this is superseded
    by the bundled runtime-jar discovery (:func:`bundled_runtime_jar`).
    """
    # .../pulse-beacon/src/inventzia/pulse/beacon/core/crosslanguage/jpype_host.py
    # parents[6] is the pulse-beacon repo root.
    beacon_root = Path(__file__).resolve().parents[6]
    return beacon_root / "core" / "java" / "jars"


def resolve_classpath(jars_dir=None) -> tuple[list[str], str]:
    """Resolve the Beacon classpath: explicit dir, else bundled jar, else staged jars.

    Returns ``(jars, source_description)``. Raises ``FileNotFoundError`` if none resolve.
    """
    if jars_dir is not None:
        jars = sorted(glob.glob(str(Path(jars_dir) / "*.jar")))
        if jars:
            return jars, f"{len(jars)} jars from {jars_dir}"
        raise FileNotFoundError(f"no jars in {jars_dir}")

    bundled = bundled_runtime_jar()
    if bundled is not None:
        return [str(bundled)], f"bundled runtime jar {bundled}"

    staged = default_jars_dir()
    jars = sorted(glob.glob(str(staged / "*.jar")))
    if jars:
        return jars, f"{len(jars)} staged jars from {staged}"

    raise FileNotFoundError(
        f"no Beacon classpath found. Either install the package (bundled runtime jar), "
        f"or build the staged jars into {staged} with pulse-beacon/build-runtime-jar.sh "
        f"(or: cd core/java && mvn package -DskipTests && "
        f"mvn dependency:copy-dependencies -DoutputDirectory=jars -DincludeScope=runtime && "
        f"cp target/pulse-beacon-core-*.jar jars/)")


def start_jvm(jars_dir=None) -> None:
    """Start the embedded JVM on the Beacon classpath, if not already running.

    Classpath resolution order: explicit ``jars_dir`` → wheel-bundled runtime jar →
    source-tree staged jars (see :func:`resolve_classpath`). The JVM is located via
    ``JAVA_HOME``; in the shared ``pulse`` conda env that is set automatically by the
    bundled OpenJDK, so no manual export is needed; outside it, point ``JAVA_HOME`` at
    any JDK 17+. Idempotent.
    """
    import jpype

    if jpype.isJVMStarted():
        _log.large_info("JVM already running; reusing it")
        return

    jars, source = resolve_classpath(jars_dir)

    if "JAVA_HOME" not in os.environ:
        # A jar is not a JVM. In the `pulse` env JAVA_HOME is set automatically;
        # outside it, set it explicitly.
        raise EnvironmentError(
            "JAVA_HOME is not set; run in the `pulse` conda env, or point it at a JDK 17+")

    # convertStrings=True: Java String returns (topic names, tagged JSON) come
    # back as native Python str, so json.loads / str ops work without wrapping.
    jpype.startJVM(classpath=jars, convertStrings=True)
    _log.info(f"JVM started ({source})")
