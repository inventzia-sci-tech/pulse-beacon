# SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
# Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
#
# This file is part of pulse-beacon.
#
# pulse-beacon is dual-licensed:
#   - Under the GNU Affero General Public License v3.0 or later (see LICENSE-AGPL-3.0).
#   - Under a commercial license (see LICENSE-COMMERCIAL.txt).
#     Contact operations@inventzia.com.
"""Pytest bootstrap for the pulse-beacon Python tests.

Puts the two repo roots on ``sys.path`` so the repo-root-prefixed imports
(``core.python.*``, ``datum.python.*``, ``schemas.schemas_py.*``) resolve no
matter where pytest is invoked from — the test-time mirror of the runtime
``PYTHONPATH="pulse-beacon:pulse-data"``.
"""

import sys
from pathlib import Path

# .../pulse-beacon/core/python/tests/conftest.py -> parents[3] is the pulse-beacon root.
_BEACON_ROOT = Path(__file__).resolve().parents[3]
_DATA_ROOT = _BEACON_ROOT.parent / "pulse-data"

for _root in (_BEACON_ROOT, _DATA_ROOT):
    _p = str(_root)
    if _p not in sys.path:
        sys.path.insert(0, _p)
