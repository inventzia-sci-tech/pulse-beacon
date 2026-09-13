# SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
# Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
#
# This file is part of pulse-beacon.
#
# pulse-beacon is dual-licensed:
#   - Under the GNU Affero General Public License v3.0 or later (see LICENSE-AGPL-3.0).
#   - Under a commercial license (see LICENSE-COMMERCIAL.txt).
#     Contact operations@inventzia.com.
"""Composite datum-type registry SPI (pulse-data), Phase 1.

Covers the core-seeded default registry, isolated construction from an explicit provider
list, and the construction-time validation rules. Pure Python: no JVM required.
"""

import pytest

from inventzia.pulse.data.datum.provider import DatumTypeBinding
from inventzia.pulse.data.datum.registry import build_registry, default_registry
from inventzia.pulse.data.schemas.provider import CoreDatumTypeProvider
from inventzia.pulse.data.schemas.platform.heart_beat import HeartBeat


_CORE_NS = "com.inventzia.pulse.data"   # a namespace root that covers HeartBeat.TYPE_ID


def _provider(provider_id=_CORE_NS, spi=1, version="1.0", bindings=None, manifest=None):
    """A minimal provider stand-in. Defaults to a valid HeartBeat binding so each test
    varies exactly one thing and triggers exactly one rule."""
    if bindings is None:
        bindings = [DatumTypeBinding(HeartBeat.TYPE_ID, HeartBeat.TYPE_VERSION, HeartBeat)]

    class _P:
        def provider_id(self): return provider_id
        def spi_version(self): return spi
        def package_version(self): return version
        def bindings(self): return bindings
        def manifest(self): return manifest

    return _P()


def test_default_registry_seeds_core():
    reg = default_registry()
    assert reg.class_for(HeartBeat.TYPE_ID) is HeartBeat
    assert reg.type_id_of(HeartBeat(beatKey="B", beatTime=1)) == HeartBeat.TYPE_ID


def test_isolated_registry_from_explicit_providers():
    reg = build_registry([CoreDatumTypeProvider()])
    assert reg.class_for(HeartBeat.TYPE_ID) is HeartBeat


@pytest.mark.parametrize("provider, why", [
    (_provider(spi=2), "spi version mismatch"),
    (_provider(version=""), "blank package_version"),
    (_provider(provider_id="nodot"), "malformed provider_id"),
    (_provider(bindings=[]), "empty provider"),
    (_provider(provider_id="com.evil"), "TYPE_ID outside the provider namespace"),
    (_provider(bindings=[DatumTypeBinding(HeartBeat.TYPE_ID, 0, HeartBeat)]), "non-positive TYPE_VERSION"),
    (_provider(bindings=[DatumTypeBinding(HeartBeat.TYPE_ID, HeartBeat.TYPE_VERSION + 1, HeartBeat)]),
     "type_version disagrees with the class TYPE_VERSION (positive, but drifted)"),
    (_provider(bindings=[DatumTypeBinding(_CORE_NS + ".Wrong", 1, HeartBeat)]), "descriptor drift"),
    (_provider(bindings=[DatumTypeBinding(_CORE_NS + ".Bad", 1, dict)]), "class is not a pydantic model"),
])
def test_validation_rejects(provider, why):
    with pytest.raises(ValueError):
        build_registry([provider])


def test_duplicate_type_id_across_providers_is_rejected():
    # Two providers with distinct provider_ids (so no duplicate-provider error) both
    # contributing HeartBeat's real TYPE_ID.
    p1 = _provider(provider_id="com.inventzia.pulse.data")
    p2 = _provider(provider_id="com.inventzia.pulse.data.schemas")
    with pytest.raises(ValueError):
        build_registry([p1, p2])


# --- Phase 2: manifest retention, validation, fingerprint ------------------------------

def test_fingerprint_none_and_provider_unverifiable_when_manifest_absent():
    reg = build_registry([_provider(manifest=None)])
    assert reg.fingerprint() is None
    assert reg.unverifiable_providers() == (_CORE_NS,)
    assert reg.providers()[0].package_version == "1.0"


def test_malformed_manifest_is_rejected():
    with pytest.raises(ValueError):
        build_registry([_provider(manifest="not-a-pdm1-manifest")])


def test_manifest_disagreeing_with_bindings_is_rejected():
    # Manifest declares a type the provider does not bind (one-to-one violated).
    bad = f"pdm1|{_CORE_NS}|{_CORE_NS}.Other:1:{'0' * 64}"
    with pytest.raises(ValueError):
        build_registry([_provider(manifest=bad)])


def test_manifest_with_non_hex_fingerprint_is_rejected():
    bad = f"pdm1|{_CORE_NS}|{HeartBeat.TYPE_ID}:{HeartBeat.TYPE_VERSION}:not-hex"
    with pytest.raises(ValueError):
        build_registry([_provider(manifest=bad)])
