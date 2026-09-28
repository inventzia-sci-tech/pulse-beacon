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

import java.time.Instant;
import java.util.Objects;

/**
 * One row of the {@code pulse_tables} registry: what a data table holds, and who said so.
 *
 * <p>One row per table rather than per event. The {@code schemaFingerprint} is the part that earns its
 * place — a bare type identifier waves through <b>same type, changed schema</b>, which is the failure
 * that produces a table quietly holding two different shapes of the same nominal type.
 *
 * @param table                 the fully qualified table; the name alone is ambiguous
 * @param typeId                e.g. {@code com.inventzia.pulse.data.schemas.marketdata.CdfBar}
 * @param typeVersion           the datum's {@code TYPE_VERSION}
 * @param schemaFingerprint     per-type {@code sha256} from pulse-data's provider manifest, or
 *                              {@code null} when the provider has no manifest and is unverifiable
 * @param storageMappingVersion which SQL representation of that schema (§4)
 * @param state                 provisioning or ready
 * @param createdAt             provenance: when
 * @param createdBy             provenance: who
 */
public record TableRegistration(QualifiedTableName table,
                                String typeId,
                                int typeVersion,
                                String schemaFingerprint,
                                int storageMappingVersion,
                                ProvisioningState state,
                                Instant createdAt,
                                String createdBy) {

    public TableRegistration {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(typeId, "typeId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /** Whether this row describes the same binding a gateway is asking for. */
    public boolean matches(StorageMapping mapping, String expectedFingerprint) {
        return typeId.equals(mapping.typeId())
               && typeVersion == mapping.typeVersion()
               && storageMappingVersion == StorageMapping.MAPPING_VERSION
               && fingerprintMatches(expectedFingerprint);
    }

    /**
     * An unverifiable provider (no manifest) has no fingerprint on either side; that is a known,
     * reported gap rather than a match, and it must not be silently treated as one when only one side
     * is missing.
     */
    private boolean fingerprintMatches(String expected) {
        return Objects.equals(schemaFingerprint, expected);
    }

    /** Why this row does not match, for an error a human can act on. */
    public String describeMismatch(StorageMapping mapping, String expectedFingerprint) {
        StringBuilder sb = new StringBuilder();
        if (!typeId.equals(mapping.typeId())) {
            sb.append("\n  - registered for type '").append(typeId)
              .append("' but the gateway binds '").append(mapping.typeId()).append('\'');
        }
        if (typeVersion != mapping.typeVersion()) {
            sb.append("\n  - registered type version ").append(typeVersion)
              .append(" but the gateway binds ").append(mapping.typeVersion());
        }
        if (!fingerprintMatches(expectedFingerprint)) {
            sb.append("\n  - schema fingerprint differs: table was written with ")
              .append(describe(schemaFingerprint)).append(", this build has ")
              .append(describe(expectedFingerprint))
              .append(" (same type, changed schema)");
        }
        if (storageMappingVersion != StorageMapping.MAPPING_VERSION) {
            sb.append("\n  - storage mapping version ").append(storageMappingVersion)
              .append(" but this build produces ").append(StorageMapping.MAPPING_VERSION)
              .append(" (the same schema laid out differently)");
        }
        return sb.toString();
    }

    private static String describe(String fingerprint) {
        return fingerprint == null ? "no manifest (unverifiable)" : fingerprint;
    }
}
