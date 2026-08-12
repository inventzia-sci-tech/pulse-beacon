# SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
# Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
#
# This file is part of pulse-beacon.
#
# pulse-beacon is dual-licensed:
#   - Under the GNU Affero General Public License v3.0 or later (see LICENSE-AGPL-3.0).
#   - Under a commercial license (see LICENSE-COMMERCIAL.txt).
#     Contact operations@inventzia.com.
"""SPI Phase 3: the cross-language type-universe gate.

The JPype bridge compares the Python and Java composite fingerprints once the JVM is up
and fails fast on a mismatch, before any event flows.
"""

from typing import ClassVar

import pydantic
import pytest

from inventzia.pulse.beacon.core.crosslanguage.jpype_host import (
    TypeUniverseMismatch,
    verify_type_universe,
)

pytestmark = pytest.mark.integration


def test_core_universe_verifies(beacon_jvm):
    fp = verify_type_universe()          # default registries: Python core == Java core
    assert fp is not None and len(fp) == 64


def test_mismatch_is_detected_and_names_the_extra_type(beacon_jvm):
    from inventzia.pulse.data.datum.provider import DatumTypeBinding
    from inventzia.pulse.data.datum.registry import build_registry
    from inventzia.pulse.data.schemas.provider import CoreDatumTypeProvider

    # A model + provider that exist only on the Python side, so the fingerprints diverge.
    class ExtraDatum(pydantic.BaseModel):
        TYPE_ID: ClassVar[str] = "com.ext.Extra"
        TYPE_VERSION: ClassVar[int] = 1
        key: str

        @property
        def datum_key(self) -> str:
            return self.key

        @property
        def datum_time(self) -> int:
            return 0

    class ExtProvider:
        def provider_id(self): return "com.ext"
        def spi_version(self): return 1
        def package_version(self): return "1.0"
        def bindings(self): return [DatumTypeBinding("com.ext.Extra", 1, ExtraDatum)]
        def manifest(self): return "pdm1|com.ext|com.ext.Extra:1:" + ("a" * 64)

    divergent = build_registry([CoreDatumTypeProvider(), ExtProvider()])

    with pytest.raises(TypeUniverseMismatch) as excinfo:
        verify_type_universe(py_registry=divergent)   # compared against the real Java core
    message = str(excinfo.value)
    assert "com.ext.Extra" in message
    assert "only in Python" in message
