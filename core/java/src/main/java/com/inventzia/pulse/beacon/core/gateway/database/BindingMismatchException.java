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
 * A table's registry row does not describe the binding a gateway is asking for.
 *
 * <p>Refusing is the whole point: reading a table as a type it does not hold, or appending to one whose
 * schema has changed underneath, produces data that silently disagrees with the run that claims to have
 * produced it. The message names every difference at once so the operator can decide whether to migrate
 * the table or fix the binding.
 */
public class BindingMismatchException extends RuntimeException {

    private final QualifiedTableName table;

    public BindingMismatchException(QualifiedTableName table, String detail) {
        super("table " + table.qualified() + " does not match the requested binding:" + detail);
        this.table = table;
    }

    /** The table whose registration disagreed. */
    public QualifiedTableName table() {
        return table;
    }
}
