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
Cross-language codec field-parity guard.

The two languages share a forward-compatibility policy: both tolerate unknown
fields on read (Java ``DatumCodec`` disables ``FAIL_ON_UNKNOWN_PROPERTIES``;
generated Python models use ``extra="ignore"``). That tolerance is what lets a
newer producer add a field without breaking an old consumer — but it also means a
*bug* where one language emits a field the other does not (e.g. Jackson once
leaked ``getDatumKey()``/``getDatumTime()`` as ``datumKey``/``datumTime``) is now
silently swallowed instead of raising.

This test restores that safety net: it round-trips each datum's tagged JSON
*through the other language* and asserts the envelope is byte-for-byte identical
(parsed). If either side serialises a field the other ignores, the ignoring side
drops it on re-encode and the envelopes diverge — failing loudly. It is the
drift detector that ``extra="forbid"`` used to provide, but without sacrificing
forward compatibility.

Integration test (``integration`` marker). The ``beacon_jvm`` fixture resolves the
Beacon classpath via ``resolve_classpath()`` (wheel-bundled runtime jar or staged
jars), so it exercises the installed artifact. Missing prerequisites skip locally
but fail under ``PULSE_REQUIRE_INTEGRATION`` (release CI).
"""

import json

import pytest

from inventzia.pulse.data.datum.codec import from_tagged_json, to_tagged_json
from inventzia.pulse.data.schemas.platform.heart_beat import HeartBeat
from inventzia.pulse.data.schemas.platform.text_message import TextMessage

pytestmark = pytest.mark.integration


@pytest.fixture(scope="module", autouse=True)
def _jvm(beacon_jvm):
    # beacon_jvm resolves the classpath (bundled jar or staged jars) and starts the JVM.
    pass


def _java_codec():
    from jpype import JClass
    return JClass("com.inventzia.pulse.data.datum.DatumCodec").instance()


@pytest.mark.parametrize(
    "datum",
    [
        TextMessage(msgKey="ALERT", msgTime=1_283_630_000_000, text="hello world"),
        HeartBeat(beatKey="BEAT", beatTime=1_283_630_001_500),
    ],
    ids=["TextMessage", "HeartBeat"],
)
def test_tagged_envelope_is_identical_round_tripped_through_java(datum):
    codec = _java_codec()

    p1 = to_tagged_json(datum)                      # Python serialises
    java_obj = codec.fromTaggedJson(p1)             # Java decodes
    j1 = str(codec.toTaggedJson(java_obj))          # Java re-serialises
    p2 = to_tagged_json(from_tagged_json(j1))       # Python round-trips Java's output

    # Identity through the foreign language. Any field one side emits but the
    # other does not is dropped on the ignoring side's re-encode, diverging these.
    assert json.loads(p1) == json.loads(j1) == json.loads(p2)
