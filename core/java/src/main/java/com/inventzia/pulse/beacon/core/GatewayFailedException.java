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
package com.inventzia.pulse.beacon.core;

/**
 * A gateway whose failure was declared fatal ended the run.
 *
 * <p>Thrown by the engine after the dispatch loop has unwound, so that a run built on partial history
 * fails rather than completing. Distinct from an engine fault: nothing in the engine went wrong — it
 * is refusing to call a half-finished replay a success.
 *
 * <p>See {@code docs/pulse-sql-gateway.md} §8.
 */
public class GatewayFailedException extends Exception {

    public GatewayFailedException(String message) {
        super("run failed because a gateway failed: " + message);
    }
}
