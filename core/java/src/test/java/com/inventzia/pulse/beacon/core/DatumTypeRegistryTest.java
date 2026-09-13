/*
 * SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
 * Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
 *
 * This file is part of pulse-beacon.
 *
 * pulse-beacon is dual-licensed:
 *   - Under the GNU Affero General Public License v3.0 or later (see LICENSE-AGPL-3.0).
 *   - Under a commercial license (see LICENSE-COMMERCIAL.txt).
 *     Contact operations@inventzia.com.
 */
package com.inventzia.pulse.beacon.core;

import com.inventzia.pulse.data.datum.Datum;
import com.inventzia.pulse.data.datum.DatumCodec;
import com.inventzia.pulse.data.datum.DatumTypeBinding;
import com.inventzia.pulse.data.datum.DatumTypeProvider;
import com.inventzia.pulse.data.datum.DatumTypeRegistry;
import com.inventzia.pulse.data.schemas.CoreDatumTypeProvider;
import com.inventzia.pulse.data.schemas.platform.HeartBeat;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The composite {@link DatumTypeRegistry} SPI (pulse-data), Phase 1: the core-seeded default
 * registry, isolated construction from explicit providers, a registry-bound codec, and the
 * construction-time validation rules. Java mirror of the Python {@code test_datum_registry_spi}.
 */
class DatumTypeRegistryTest {

    private static final String CORE_NS = "com.inventzia.pulse.data"; // covers HeartBeat.TYPE_ID

    @Test
    void defaultRegistrySeedsCore() {
        DatumTypeRegistry reg = DatumTypeRegistry.defaultRegistry();
        assertThat(reg.classFor(HeartBeat.TYPE_ID)).isEqualTo(HeartBeat.class);
        assertThat(reg.typeIdOf(new HeartBeat("B", 1L))).isEqualTo(HeartBeat.TYPE_ID);
    }

    @Test
    void isolatedRegistryAndRegistryBoundCodecRoundTrip() {
        DatumTypeRegistry reg = DatumTypeRegistry.of(new CoreDatumTypeProvider());
        DatumCodec codec = DatumCodec.forRegistry(reg);
        HeartBeat hb = new HeartBeat("B", 1_000L);
        assertThat(codec.fromTaggedJson(codec.toTaggedJson(hb))).isEqualTo(hb);
    }

    @Test
    void validationRejectsBadProviders() {
        DatumTypeBinding ok = new DatumTypeBinding(HeartBeat.TYPE_ID, HeartBeat.TYPE_VERSION, HeartBeat.class);

        assertThatThrownBy(() -> DatumTypeRegistry.of(provider(CORE_NS, 2, "1.0", List.of(ok))))
                .as("spi mismatch").isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DatumTypeRegistry.of(provider(CORE_NS, 1, "", List.of(ok))))
                .as("blank package version").isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DatumTypeRegistry.of(provider("nodot", 1, "1.0", List.of(ok))))
                .as("malformed provider id").isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DatumTypeRegistry.of(provider(CORE_NS, 1, "1.0", List.of())))
                .as("empty provider").isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DatumTypeRegistry.of(provider("com.evil", 1, "1.0", List.of(ok))))
                .as("type id outside namespace").isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DatumTypeRegistry.of(provider(CORE_NS, 1, "1.0",
                List.of(new DatumTypeBinding(HeartBeat.TYPE_ID, 0, HeartBeat.class)))))
                .as("non-positive type version").isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DatumTypeRegistry.of(provider(CORE_NS, 1, "1.0",
                List.of(new DatumTypeBinding(HeartBeat.TYPE_ID, HeartBeat.TYPE_VERSION + 1, HeartBeat.class)))))
                .as("type version drift (positive, but disagrees with class TYPE_VERSION)")
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DatumTypeRegistry.of(provider(CORE_NS, 1, "1.0",
                List.of(new DatumTypeBinding(CORE_NS + ".Wrong", 1, HeartBeat.class)))))
                .as("descriptor drift").isInstanceOf(IllegalStateException.class);
    }

    @Test
    void duplicateTypeIdAcrossProvidersIsRejected() {
        DatumTypeBinding hb = new DatumTypeBinding(HeartBeat.TYPE_ID, HeartBeat.TYPE_VERSION, HeartBeat.class);
        DatumTypeProvider p1 = provider("com.inventzia.pulse.data", 1, "1.0", List.of(hb));
        DatumTypeProvider p2 = provider("com.inventzia.pulse.data.schemas", 1, "1.0", List.of(hb));
        assertThatThrownBy(() -> DatumTypeRegistry.of(p1, p2)).isInstanceOf(IllegalStateException.class);
    }

    private static DatumTypeProvider provider(String id, int spi, String version,
                                              Collection<DatumTypeBinding> bindings) {
        return new DatumTypeProvider() {
            @Override public String providerId() { return id; }
            @Override public int spiVersion() { return spi; }
            @Override public String packageVersion() { return version; }
            @Override public Collection<DatumTypeBinding> bindings() { return bindings; }
        };
    }
}
