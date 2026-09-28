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

import java.util.Objects;

/**
 * A gateway has failed terminally: it will produce or accept nothing more.
 *
 * <p><b>Why this exists at all.</b> A gateway that fails mid-run must still disconnect, so the
 * TimeMachine's all-drivers barrier is released and the run does not hang. But disconnecting is also
 * exactly what a gateway does when it reaches the clean end of its stream — so from the engine's side
 * the two are indistinguishable, and a replay that died halfway completes normally and is recorded as
 * {@code runStatus: completed}. This record is the channel that tells them apart, carried <em>beside</em>
 * the status transition rather than encoded into it.
 *
 * <p>See {@code docs/pulse-sql-gateway.md} §8.
 *
 * @param gatewayName the gateway that failed, so the manifest can name it
 * @param detail      what it was doing, in terms an operator can act on
 * @param cause       the underlying exception, or {@code null} if there was none
 * @param atMillis    wall-clock time of the failure
 * @param fatal       whether this failure should end the run (see {@link AbstractGateway#setFailureIsFatal})
 */
public record GatewayFailure(String gatewayName, String detail, Throwable cause,
                             long atMillis, boolean fatal) {

    public GatewayFailure {
        Objects.requireNonNull(gatewayName, "gatewayName");
        Objects.requireNonNull(detail, "detail");
    }

    /** A one-line description for a log or a run manifest. */
    public String describe() {
        return gatewayName + ": " + detail
               + (cause != null ? " (" + cause.getClass().getSimpleName() + ": "
                                  + cause.getMessage() + ")" : "");
    }

    @Override
    public String toString() {
        return "GatewayFailure[" + describe() + (fatal ? ", fatal" : ", non-fatal") + "]";
    }
}
