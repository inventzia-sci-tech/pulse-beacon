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
 * Notified when a gateway fails terminally.
 *
 * <p>Deliberately separate from {@code StatusListener}: a status transition to {@code STOPPED} is
 * ambiguous — it is equally what a healthy gateway does at the end of its stream — and overloading it
 * is how a half-finished replay comes to be recorded as a completed run.
 *
 * <p>Called on the failing gateway's own thread. Implementations must not block.
 */
@FunctionalInterface
public interface FailureListener {

    /**
     * @param failure what failed, and whether it should end the run
     */
    void onGatewayFailure(GatewayFailure failure);
}
