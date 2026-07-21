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

Integration test (``integration`` marker). The ``beacon_jvm`` fixture resolves the
Beacon classpath via ``resolve_classpath()`` — the wheel-bundled runtime jar or
source-tree staged jars — so it exercises the installed artifact. Missing
prerequisites skip locally but fail under ``PULSE_REQUIRE_INTEGRATION`` (release CI).
"""

import pytest

from inventzia.pulse.beacon.core.examples.historic_run_jpype import EXPECTED, run

pytestmark = pytest.mark.integration


def test_historic_run_parity(beacon_jvm):
    received, status = run()
    assert status == "COMPLETE"
    assert received == EXPECTED
