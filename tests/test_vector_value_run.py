# SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
# Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
#
# This file is part of pulse-beacon.
#
# pulse-beacon is dual-licensed:
#   - Under the GNU Affero General Public License v3.0 or later (see LICENSE-AGPL-3.0).
#   - Under a commercial license (see LICENSE-COMMERCIAL.txt).
#     Contact operations@inventzia.com.
"""End-to-end test: a VectorValue indicator stream through the Java engine (JPype).

Asserts the generic ``common.VectorValue`` datum flows Python-source -> Java-engine
-> Python-consumer with its decimal vector and parallel valueIds intact — the real
engine + codec path, not just a codec unit test.
"""

import pytest

from inventzia.pulse.beacon.core.examples.vector_value_run_jpype import EXPECTED, run

pytestmark = pytest.mark.integration


def test_vector_value_run(beacon_jvm):
    received, status = run()
    assert status == "COMPLETE"
    assert received == EXPECTED
