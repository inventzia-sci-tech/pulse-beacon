# SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
# Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
#
# This file is part of pulse-beacon.
#
# pulse-beacon is dual-licensed:
#   - Under the GNU Affero General Public License v3.0 or later (see LICENSE-AGPL-3.0).
#   - Under a commercial license (see LICENSE-COMMERCIAL.txt).
#     Contact operations@inventzia.com.
"""Immutability / validity of the generated datum classes (bus guarantee).

A datum must not be mutable or become invalid after construction. Exercises the
generated `VectorValue` in both languages: Python (frozen model + tuple sequences,
required-field rejection) and Java (defensive `List.copyOf` + `requireNonNull` in
the record's compact constructor).
"""

from decimal import Decimal

import pydantic
import pytest

from inventzia.pulse.data.schemas.common.vector_value import VectorValue


def test_python_model_is_immutable_and_validated():
    vv = VectorValue(key="X", time=1, values=[Decimal("1"), Decimal("2")], valueIds=["a", "b"])

    assert isinstance(vv.values, tuple)                 # immutable sequence, not list
    assert not hasattr(vv.values, "append")

    with pytest.raises(pydantic.ValidationError):       # frozen: no field reassignment
        vv.time = 999

    with pytest.raises(pydantic.ValidationError):       # required field cannot be null
        VectorValue(key="X", time=1, values=None)

    with pytest.raises(pydantic.ValidationError):       # parallel-length invariant
        VectorValue(key="X", time=1, values=[Decimal("1")], valueIds=["a", "b"])


@pytest.mark.integration
def test_java_record_is_immutable_and_validated(beacon_jvm):
    from jpype import JClass

    VV = JClass("com.inventzia.pulse.data.schemas.common.VectorValue")
    BigDecimal = JClass("java.math.BigDecimal")
    ArrayList = JClass("java.util.ArrayList")
    JList = JClass("java.util.List")

    # Defensive copy: mutating the caller's original list must not change the record.
    original = ArrayList()
    original.add(BigDecimal("1"))
    original.add(BigDecimal("2"))
    vv = VV("k", 1, original, None)
    original.add(BigDecimal("3"))
    assert vv.values().size() == 2

    # The record's list is unmodifiable (accessor can't be used to mutate state).
    with pytest.raises(Exception):
        vv.values().add(BigDecimal("9"))

    # Required field rejected when null.
    with pytest.raises(Exception):
        VV("k", 1, None, None)

    # Parallel-length invariant enforced at construction.
    with pytest.raises(Exception):
        VV("k", 1, JList.of(BigDecimal("1"), BigDecimal("2")), JList.of("only-one"))
