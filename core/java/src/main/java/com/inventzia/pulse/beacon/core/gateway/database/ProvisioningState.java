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

/**
 * Where a table is in the two-step act of being created and registered.
 *
 * <p>The state machine exists because <b>creating a table and registering it cannot be made atomic
 * portably</b>. MySQL performs an implicit commit on {@code CREATE TABLE}, so a caller's rollback
 * cannot undo it, and a crash between the two statements leaves a table nobody recorded. Rather than
 * claim a guarantee only some dialects can keep, the intermediate state is written down: insert the
 * row as {@link #PROVISIONING}, create the table, mark it {@link #READY}.
 *
 * <p>The payoff is that the two failure shapes become <em>recoverable states an adapter can report</em>
 * rather than corruption to be puzzled over: a {@code provisioning} row with no table, or a table with
 * no row. See {@code docs/pulse-sql-gateway.md} §5.
 */
public enum ProvisioningState {

    /** The registry row exists; the table may or may not. Not usable by a source or a sink. */
    PROVISIONING,

    /** The table exists and matches its registry row. The only state ordinary startup accepts. */
    READY;

    /** The stored form. Lowercase, so a human reading the table sees what the docs say. */
    public String stored() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /** Parse the stored form. */
    public static ProvisioningState parse(String stored) {
        for (ProvisioningState s : values()) {
            if (s.stored().equalsIgnoreCase(stored)) return s;
        }
        throw new IllegalArgumentException("unknown provisioning state: " + stored);
    }
}
