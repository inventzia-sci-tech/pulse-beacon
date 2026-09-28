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

import java.util.List;

/**
 * A datum type has fields this mapping cannot represent exactly.
 *
 * <p>Raised at startup, naming every offending field at once rather than one per attempt, because
 * the fix is usually to the schema or the mapping and the author wants the whole list. Refusing is
 * deliberate: a field quietly widened to text, or a decimal quietly stored as a float, turns a round
 * trip into a lossy one and the loss surfaces much later as data that no longer matches the run that
 * produced it.
 */
public class UnsupportedMappingException extends RuntimeException {

    private final String typeId;
    private final List<String> reasons;

    public UnsupportedMappingException(String typeId, List<String> reasons) {
        super("cannot map " + typeId + " to a table:\n  - " + String.join("\n  - ", reasons));
        this.typeId = typeId;
        this.reasons = List.copyOf(reasons);
    }

    /** The datum type that could not be mapped. */
    public String typeId() {
        return typeId;
    }

    /** One entry per field that could not be mapped, each naming the field and why. */
    public List<String> reasons() {
        return reasons;
    }
}
