# SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
# Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
#
# This file is part of pulse-beacon.
#
# pulse-beacon is dual-licensed:
#   - Under the GNU Affero General Public License v3.0 or later (see LICENSE-AGPL-3.0).
#   - Under a commercial license (see LICENSE-COMMERCIAL.txt).
#     Contact operations@inventzia.com.
"""Parity test for the JPype Python-host historical run.

Asserts that the cross-language run reproduces the all-Java ``HistoricRunExample``
exactly: the Python ``PrintConsumer`` receives the same event-time-ordered merge,
and the engine reaches ``COMPLETE``. This is the CI form of the ``PARITY OK`` the
example prints when run by hand.

Integration test — requires the Java module's release jars staged in
``core/java/jars/`` (build them per the README). If they are absent the test is
skipped rather than failed, so a checkout without a Java build still collects.
"""

import pytest

from core.python.inventzia.pulse.beacon.core.crosslanguage import jpype_host
from core.python.inventzia.pulse.beacon.core.examples.historic_run_jpype import EXPECTED, run


@pytest.fixture(scope="module", autouse=True)
def _require_staged_jars():
    jars_dir = jpype_host.default_jars_dir()
    if not list(jars_dir.glob("*.jar")):
        pytest.skip(f"no staged jars in {jars_dir}; build core/java first (see README)")


def test_historic_run_parity():
    received, status = run()
    assert status == "COMPLETE"
    assert received == EXPECTED
