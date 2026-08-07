# SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
# Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
#
# This file is part of pulse-beacon.
#
# pulse-beacon is dual-licensed:
#   - Under the GNU Affero General Public License v3.0 or later (see LICENSE-AGPL-3.0).
#   - Under a commercial license (see LICENSE-COMMERCIAL.txt).
#     Contact operations@inventzia.com.
"""Encode-side registry validation (Python parity with Java DatumTypeRegistry).

Tagged encoding must verify the class-to-TYPE_ID binding against the registry, so a
producer cannot emit an envelope whose typeId no runtime can decode. This mirrors the
Java `typeIdOf` check. Pure Python: no JVM required.
"""

import pytest

from inventzia.pulse.data.datum.codec import to_tagged_json
from inventzia.pulse.data.schemas.platform.heart_beat import HeartBeat
from inventzia.pulse.data.schemas.registry import type_id_of


def test_registered_datum_encodes():
    hb = HeartBeat(beatKey="B", beatTime=1)
    assert type_id_of(hb) == HeartBeat.TYPE_ID
    assert to_tagged_json(hb)  # produces an envelope


def test_unregistered_class_is_rejected():
    class Rogue:
        TYPE_ID = "com.example.Rogue"

    with pytest.raises(KeyError):
        type_id_of(Rogue())


def test_class_declaring_a_registered_type_id_but_not_the_binding_is_rejected():
    # A different class claiming a real TYPE_ID must not pass: the registry binds that
    # TYPE_ID to HeartBeat, not to this impostor.
    class FakeHeartBeat:
        TYPE_ID = HeartBeat.TYPE_ID

    with pytest.raises(KeyError):
        type_id_of(FakeHeartBeat())
