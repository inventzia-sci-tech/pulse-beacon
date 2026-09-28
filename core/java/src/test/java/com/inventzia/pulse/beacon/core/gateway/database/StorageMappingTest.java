/*
 * SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
 * Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
 *
 * This file is part of pulse-beacon.
 *
 * pulse-beacon is dual-licensed:
 *   - Under the GNU Affero General Public License v3.0 (see LICENSE-AGPL-3.0).
 *   - Under a commercial license (see LICENSE-COMMERCIAL.txt).
 *     Contact operations@inventzia.com.
 */
package com.inventzia.pulse.beacon.core.gateway.database;

import com.inventzia.pulse.data.schemas.common.VectorValue;
import com.inventzia.pulse.data.schemas.marketdata.CdfBar;
import com.inventzia.pulse.data.schemas.platform.EngineStatus;
import com.inventzia.pulse.data.schemas.platform.HeartBeat;
import com.inventzia.pulse.data.schemas.platform.TextMessage;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deriving a table's logical shape from a generated datum type.
 *
 * <p>The mapping's job is to be exact or to refuse. These tests hold it to both halves: the routing
 * roles must be identified (not guessed from values), decimals must stay decimals, and anything
 * without an exact representation must be rejected by name at startup rather than quietly widened.
 */
class StorageMappingTest {

    @Test
    void identifiesTheRoutingFieldsFromTheGeneratedConstants() {
        StorageMapping m = StorageMapping.of(EngineStatus.class);

        // The point of DATUM_KEY_FIELD / DATUM_TIME_FIELD: a record exposes getDatumKey() as a
        // value, and nothing else says which field it came from.
        assertThat(m.keyColumn().fieldName()).isEqualTo(EngineStatus.DATUM_KEY_FIELD);
        assertThat(m.timeColumn().fieldName()).isEqualTo(EngineStatus.DATUM_TIME_FIELD);
        assertThat(m.keyColumn().role()).isEqualTo(ColumnMapping.Role.KEY);
        assertThat(m.timeColumn().role()).isEqualTo(ColumnMapping.Role.TIME);
    }

    @Test
    void theRoutingTimeIsAlwaysEpochMillis() {
        for (Class<?> type : List.of(EngineStatus.class, HeartBeat.class, TextMessage.class)) {
            @SuppressWarnings("unchecked")
            StorageMapping m = StorageMapping.of(
                    (Class<? extends com.inventzia.pulse.data.datum.Datum>) type);
            assertThat(m.timeColumn().type())
                    .as("%s routing time", type.getSimpleName())
                    .isEqualTo(SqlLogicalType.INT64);
            assertThat(m.keyColumn().type()).isEqualTo(SqlLogicalType.TEXT);
        }
    }

    @Test
    void routingColumnsAreNeverNullable() {
        StorageMapping m = StorageMapping.of(TextMessage.class);
        assertThat(m.keyColumn().nullable()).isFalse();
        assertThat(m.timeColumn().nullable()).isFalse();
    }

    @Test
    void everyFieldOfTheTypeBecomesAColumn() {
        StorageMapping m = StorageMapping.of(HeartBeat.class);
        assertThat(m.columns()).hasSize(HeartBeat.class.getRecordComponents().length);
        assertThat(m.columns()).extracting(ColumnMapping::fieldName)
                .containsExactly("beatKey", "beatTime");
    }

    @Test
    void carriesTheTypeIdentityForTheRegistry() {
        StorageMapping m = StorageMapping.of(EngineStatus.class);
        assertThat(m.typeId()).isEqualTo(EngineStatus.TYPE_ID);
        assertThat(m.typeVersion()).isEqualTo(EngineStatus.TYPE_VERSION);
        assertThat(StorageMapping.MAPPING_VERSION)
                .as("the layout version travels beside the schema fingerprint")
                .isPositive();
    }

    @Test
    void decimalsStayDecimalsWithAnExplicitPrecision() {
        // CdfBar is the real case: prices that must not become floats.
        StorageMapping m = StorageMapping.of(CdfBar.class);
        List<ColumnMapping> decimals = m.columns().stream()
                .filter(c -> c.type() == SqlLogicalType.DECIMAL).toList();

        assertThat(decimals).as("CdfBar should carry decimal fields").isNotEmpty();
        assertThat(decimals).allSatisfy(c -> {
            assertThat(c.precision()).isEqualTo(StorageMapping.DEFAULT_DECIMAL_PRECISION);
            assertThat(c.scale()).isEqualTo(StorageMapping.DEFAULT_DECIMAL_SCALE);
        });
        assertThat(m.columns()).extracting(ColumnMapping::type)
                .as("a decimal must never be mapped to floating point")
                .doesNotContain(SqlLogicalType.DOUBLE);
    }

    @Test
    void nullabilityComesFromTheSchemaNotTheJavaType() {
        // The bug this guards: inferring nullability from "is it a primitive" marks every required
        // BigDecimal nullable. CdfBar has both kinds, so it tells the two apart.
        StorageMapping m = StorageMapping.of(CdfBar.class);
        Map<String, ColumnMapping> byField = m.columns().stream()
                .collect(java.util.stream.Collectors.toMap(ColumnMapping::fieldName, c -> c));

        assertThat(byField.get("op").nullable()).as("op is required").isFalse();
        assertThat(byField.get("cl").nullable()).as("cl is required").isFalse();
        assertThat(byField.get("datetime").nullable()).as("datetime is required").isFalse();
        assertThat(byField.get("vwap").nullable()).as("vwap is optional").isTrue();
        assertThat(byField.get("count").nullable()).as("count is optional").isTrue();
        assertThat(byField.get("expiry").nullable()).as("expiry is optional").isTrue();
    }

    @Test
    void payloadTimestampsAndDatesAreMappedNotRefused() {
        // They are data, not the routing contract: refusing them would reject the main market-data
        // type outright.
        StorageMapping m = StorageMapping.of(CdfBar.class);
        Map<String, ColumnMapping> byField = m.columns().stream()
                .collect(java.util.stream.Collectors.toMap(ColumnMapping::fieldName, c -> c));

        assertThat(byField.get("datetime").type()).isEqualTo(SqlLogicalType.TIMESTAMP_UTC);
        assertThat(byField.get("date").type()).isEqualTo(SqlLogicalType.DATE);
        // ...while the routing time stays epoch millis regardless.
        assertThat(m.timeColumn().type()).isEqualTo(SqlLogicalType.INT64);
    }

    @Test
    void refusesArraysByNameRatherThanGuessingARepresentation() {
        // VectorValue.values is array<decimal>, with an optional parallel array<string> beside it.
        // Native array, JSON column and child table are all defensible and change the row-to-datum
        // relationship differently, so v1 refuses rather than picking one silently.
        assertThatThrownBy(() -> StorageMapping.of(VectorValue.class))
                .isInstanceOf(UnsupportedMappingException.class)
                .hasMessageContaining(VectorValue.TYPE_ID)
                .hasMessageContaining("values")
                .hasMessageContaining("arrays are not mapped yet");
    }

    @Test
    void reportsEveryUnmappableFieldAtOnce() {
        UnsupportedMappingException e = org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedMappingException.class, () -> StorageMapping.of(VectorValue.class));

        assertThat(e.typeId()).isEqualTo(VectorValue.TYPE_ID);
        // Both array fields, so the author fixes the schema once rather than field by field.
        assertThat(e.reasons()).hasSizeGreaterThanOrEqualTo(1);
        assertThat(e.reasons()).allSatisfy(r -> assertThat(r).isNotBlank());
    }

    @Test
    void refusesAnythingThatIsNotAGeneratedRecord() {
        assertThatThrownBy(() -> StorageMapping.of(NotARecord.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not a record");
    }

    @Test
    void aTimeColumnMustBeEpochMillis() {
        // Guards the invariant directly: a mapper bug here would break the ordering every replay
        // depends on, so ColumnMapping refuses it at construction.
        assertThatThrownBy(() -> ColumnMapping.of("t", "t", SqlLogicalType.TEXT, false,
                        ColumnMapping.Role.TIME))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("epoch millis");
    }

    @Test
    void aDecimalWithoutPrecisionIsRejected() {
        assertThatThrownBy(() -> new ColumnMapping("v", "v", SqlLogicalType.DECIMAL, 0, 0,
                        false, ColumnMapping.Role.VALUE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("precision");
    }

    /** Not a record: the mapping only derives from generated datum types. */
    private static final class NotARecord implements com.inventzia.pulse.data.datum.Datum {
        @Override public String getDatumKey()  { return "k"; }
        @Override public long   getDatumTime() { return 0L; }
    }
}
