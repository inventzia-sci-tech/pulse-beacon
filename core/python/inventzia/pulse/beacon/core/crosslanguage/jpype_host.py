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
Python-host bootstrap: start an embedded JVM (via JPype) on the staged jars.

This is the only direction-specific piece for the Python-host launcher. The
classpath is the Maven-produced release jar set (D5) staged into
``core/java/jars/`` (the module jar plus its runtime dependencies); JPype finds
the JVM itself from ``JAVA_HOME``.

Everything above the bootstrap (actors, gateways, channel, dispatch, streamer)
is JVM-agnostic and unchanged from the Java-host (JEP) path.
"""

import glob
import os
from pathlib import Path

from core.python.inventzia.pulse.beacon.core.reporter import ComponentReporter

_log = ComponentReporter("jpype-host")


def default_jars_dir() -> Path:
    """The staged release jars directory: ``<repo>/core/java/jars``."""
    # .../core/python/inventzia/pulse/beacon/core/crosslanguage/jpype_host.py
    # parents[6] is the top-level core/ directory.
    core_top = Path(__file__).resolve().parents[6]
    return core_top / "java" / "jars"


def start_jvm(jars_dir=None) -> None:
    """Start the embedded JVM on the staged jars, if not already running.

    The JVM is located via ``JAVA_HOME``. In the shared ``pulse`` conda env that
    is set automatically by the bundled OpenJDK on activation, so no manual
    export is needed; outside it, point ``JAVA_HOME`` at any JDK 17+. Idempotent.
    """
    import jpype

    if jpype.isJVMStarted():
        _log.large_info("JVM already running; reusing it")
        return

    jars_dir = Path(jars_dir) if jars_dir else default_jars_dir()
    jars = sorted(glob.glob(str(jars_dir / "*.jar")))
    if not jars:
        raise FileNotFoundError(
            f"no jars in {jars_dir}. Build them from core/java with:\n"
            "  mvn package -DskipTests && "
            "mvn dependency:copy-dependencies -DoutputDirectory=jars -DincludeScope=runtime\n"
            "  cp target/pulse-beacon-core-*.jar jars/")

    if "JAVA_HOME" not in os.environ:
        # In the `pulse` env this is set automatically; outside it, set it explicitly.
        raise EnvironmentError(
            "JAVA_HOME is not set; run in the `pulse` conda env, or point it at a JDK 17+")

    # convertStrings=True: Java String returns (topic names, tagged JSON) come
    # back as native Python str, so json.loads / str ops work without wrapping.
    jpype.startJVM(classpath=jars, convertStrings=True)
    _log.info(f"JVM started ({len(jars)} jars from {jars_dir})")
