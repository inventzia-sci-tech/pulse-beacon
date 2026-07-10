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
Parity test for the JEP Java-host launcher (the mirror of test_historic_run_jpype).

Here the JVM is the host, so the run is a *Java* main (``HistoricRunJepExample``)
that boots CPython interpreters via JEP. Rather than embed JEP's native library
setup into Surefire, this test launches the example as a subprocess with the right
classpath and library paths and asserts it prints ``PARITY OK`` and exits 0 — i.e.
the Python components, reached from Java, saw exactly the all-Java event-time merge.

Integration test — requires the `pulse` env (with ``jep`` installed) plus a built
module (``target/classes`` + staged ``core/java/jars/``). Skipped otherwise.
"""

import os
import subprocess
from pathlib import Path

import pytest

_TESTS = Path(__file__).resolve()
_BEACON = _TESTS.parents[3]          # .../pulse-beacon
_NEW = _BEACON.parent                # .../New  (contains pulse-beacon + pulse-data)
_JAVA = _BEACON / "core" / "java"
_CLASSES = _JAVA / "target" / "classes"
_JARS = _JAVA / "jars"


@pytest.fixture(scope="module")
def jep_launch():
    prefix = os.environ.get("CONDA_PREFIX")
    if not prefix:
        pytest.skip("no CONDA_PREFIX; run inside the `pulse` conda env")
    prefix = Path(prefix)

    libjep = list(prefix.glob("lib/python*/site-packages/jep/libjep.so"))
    if not libjep:
        pytest.skip("jep is not installed in this env")
    jep_dir = libjep[0].parent
    jep_jar = next(iter(jep_dir.glob("jep-*.jar")), None)

    jars = list(_JARS.glob("*.jar"))
    if jep_jar is None or not _CLASSES.exists() or not jars:
        pytest.skip("build core/java first (target/classes + staged jars/); see README")

    classpath = os.pathsep.join([str(_CLASSES), *map(str, jars), str(jep_jar)])
    env = dict(os.environ)
    env["LD_LIBRARY_PATH"] = os.pathsep.join(
        [str(prefix / "lib"), str(jep_dir), env.get("LD_LIBRARY_PATH", "")])
    return jep_dir, classpath, env


def test_jep_historic_run_parity(jep_launch):
    jep_dir, classpath, env = jep_launch
    proc = subprocess.run(
        ["java", "-cp", classpath,
         f"-Djava.library.path={jep_dir}",
         f"-Dpulse.repo.root={_NEW}",
         "com.inventzia.pulse.beacon.core.examples.HistoricRunJepExample"],
        env=env, capture_output=True, text=True, timeout=120)

    assert "PARITY OK" in proc.stdout, (
        f"exit={proc.returncode}\n--- stdout ---\n{proc.stdout}\n"
        f"--- stderr (tail) ---\n{proc.stderr[-3000:]}")
    assert proc.returncode == 0
