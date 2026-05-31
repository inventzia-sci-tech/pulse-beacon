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
package com.inventzia.beacon.core;

/**
 * An entity that both publishes and subscribes on the bus.
 *
 * <p>Most user-facing components (strategies, transformers, aggregators,
 * gateways) are Actors: they consume events from some topics and emit events
 * to others. Pure producers can implement {@link Pub} only; pure consumers
 * can implement {@link Sub} only.
 */
public interface Actor extends Pub, Sub { }
