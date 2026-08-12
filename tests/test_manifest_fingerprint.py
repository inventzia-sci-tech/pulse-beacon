# SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
# Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
#
# This file is part of pulse-beacon.
#
# pulse-beacon is dual-licensed:
#   - Under the GNU Affero General Public License v3.0 or later (see LICENSE-AGPL-3.0).
#   - Under a commercial license (see LICENSE-COMMERCIAL.txt).
#     Contact operations@inventzia.com.
"""Schema-manifest fingerprint (SPI Phase 2): canonicalization behaviour and cross-language
parity of the composite fingerprint.

The canonicalization tests exercise the shared generator module (pulse-data source tree, not
the installed wheel); they skip if it is not alongside. The parity test compares the Python
and Java composite fingerprints and is an ``integration`` test.
"""

import copy
import importlib.util
from pathlib import Path

import pytest

# The shared canonicalization module is a generator tool in the pulse-data source tree.
_MANIFEST = (Path(__file__).resolve().parents[2]
             / "pulse-data" / "schemas" / "schemas-generators" / "manifest.py")


def _load_manifest_module():
    if not _MANIFEST.exists():
        pytest.skip(f"generator manifest module not found at {_MANIFEST}")
    spec = importlib.util.spec_from_file_location("_pd_manifest", _MANIFEST)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def _schema(**over):
    s = {
        "$schema": "http://json-schema.org/draft-07/schema#",
        "$id": "com.example.T",
        "title": "T",
        "description": "a type",
        "type": "object",
        "additionalProperties": False,
        "properties": {
            "k": {"type": "string", "description": "key", "x-datum-key": True},
            "t": {"type": "integer", "format": "int64", "x-datum-time": True},
            "v": {"type": "number", "format": "decimal", "description": "a value"},
        },
        "required": ["k", "t"],
    }
    s.update(over)
    return s


def test_description_only_change_does_not_move_the_fingerprint():
    m = _load_manifest_module()
    a = _schema()
    b = copy.deepcopy(a)
    b["description"] = "totally different prose"
    b["properties"]["v"]["description"] = "changed"
    assert m.type_fingerprint(a) == m.type_fingerprint(b)


@pytest.mark.parametrize("mutate", [
    lambda s: s["properties"]["v"].__setitem__("format", "int64"),   # format change
    lambda s: s["properties"].__setitem__("w", {"type": "string"}),  # added field
    lambda s: s["required"].append("v"),                             # requiredness change
    lambda s: s["properties"]["t"].__setitem__("x-datum-time", False) or
              s["properties"]["v"].__setitem__("x-datum-time", True),  # time binding moved
])
def test_wire_relevant_change_moves_the_fingerprint(mutate):
    m = _load_manifest_module()
    a = _schema()
    b = copy.deepcopy(a)
    mutate(b)
    assert m.type_fingerprint(a) != m.type_fingerprint(b)


def test_unsupported_wire_keyword_fails_generation():
    m = _load_manifest_module()
    s = _schema()
    s["properties"]["v"]["minimum"] = 0   # numeric constraint: not in the supported subset
    with pytest.raises(m.UnsupportedSchema):
        m.type_fingerprint(s)


@pytest.mark.integration
def test_python_and_java_composite_fingerprints_match(beacon_jvm):
    from inventzia.pulse.data.datum.registry import default_registry

    py_fp = default_registry().fingerprint()
    assert py_fp is not None

    from jpype import JClass
    reg = JClass("com.inventzia.pulse.data.datum.DatumTypeRegistry")
    java_fp = str(reg.defaultRegistry().fingerprint())
    assert py_fp == java_fp
