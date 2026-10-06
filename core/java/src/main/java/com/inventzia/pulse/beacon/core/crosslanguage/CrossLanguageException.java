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
package com.inventzia.pulse.beacon.core.crosslanguage;

/** Unchecked exception thrown by the cross-language gateways when the bridge fails. */
public class CrossLanguageException extends RuntimeException {
    /** Serialised only if a caller chooses to; fixed so a future field cannot silently
     *  change the identity of an already-serialised instance. */
    private static final long serialVersionUID = 1L;


    public CrossLanguageException(String message) {
        super(message);
    }

    public CrossLanguageException(String message, Throwable cause) {
        super(message, cause);
    }
}
