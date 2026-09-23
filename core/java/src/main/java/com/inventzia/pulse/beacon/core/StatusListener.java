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
 * Observer of a component's lifecycle transitions.
 *
 * <p>Every {@link AbstractGateway} — the engine included — routes its status changes through a
 * single {@code setStatus} choke point, and notifies its listeners from there. This is the seam the
 * engine uses to turn its own and its gateways' transitions into published
 * {@link com.inventzia.pulse.data.schemas.platform.EngineStatus} events, so a run's lifecycle is
 * observable through the same mechanism as its data.
 *
 * <p><b>Contract.</b> Called synchronously on whatever thread changed the status, so an
 * implementation must be quick and must not block. Faults are isolated and logged by the notifier:
 * a broken listener never disturbs the component it is watching.
 */
@FunctionalInterface
public interface StatusListener {

    /**
     * @param component the name of the component whose status changed
     * @param from      the status it left
     * @param to        the status it entered
     * @param atMillis  wall-clock time of the transition, epoch milliseconds
     */
    void onStatusChanged(String component, GatewayStatus from, GatewayStatus to, long atMillis);
}
