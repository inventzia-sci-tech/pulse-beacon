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

import java.util.List;

/**
 * A node in the routing graph: it can publish, subscribe, run on its own
 * thread, and act as a registration hub for other gateways.
 *
 * <p>Gateway is the hub that conveys events between publishers and
 * subscribers. Both leaf gateways (file, socket, database, external feed) and
 * the engine itself implement Gateway. Any publisher or subscriber registers
 * its interest in sending or receiving on this gateway.
 *
 * <p>Registration is per {@code (topic, key)}: a subscriber declares the set of
 * key values it cares about on a topic, and the gateway routes only matching
 * events to it.
 */
public interface Gateway extends Actor, Runnable {

    // ------------------------------------------------------------------
    // Subscription registry
    // ------------------------------------------------------------------

    /**
     * Register a subscriber for a topic and a set of keys.
     *
     * @param subscriber the gateway that wishes to receive the data
     * @param topic      the topic of the data
     * @param keys       the key values on that topic the subscriber wants
     */
    void registerSubscriber(Gateway subscriber, Topic<?> topic, List<String> keys);

    /**
     * Stop an existing subscription for a topic and a set of keys.
     *
     * @param subscriber the gateway that wishes to stop receiving the data
     * @param topic      the topic of the data
     * @param keys       the key values to unsubscribe from
     */
    void unregisterSubscriber(Gateway subscriber, Topic<?> topic, List<String> keys);

    /**
     * Declare that a publisher will send updates for a topic and a set of keys.
     *
     * @param publisher the gateway that will publish the data
     * @param topic     the topic of the data
     * @param keys      the key values the publisher will publish
     */
    void registerPublisher(Gateway publisher, Topic<?> topic, List<String> keys);

    /**
     * Declare that a publisher is stopping its updates for a topic and keys.
     *
     * @param publisher the gateway that will stop publishing
     * @param topic     the topic of the data
     * @param keys      the key values to stop publishing
     */
    void unregisterPublisher(Gateway publisher, Topic<?> topic, List<String> keys);

    // ------------------------------------------------------------------
    // Simulation window
    // ------------------------------------------------------------------

    /** Set the [start, end] window (epoch millis) this gateway operates over. */
    void setStartEnd(long startTime, long endTime);

    // ------------------------------------------------------------------
    // Clock driving (simulation determinism)
    // ------------------------------------------------------------------

    /**
     * Whether the data produced by this gateway is responsible for being a
     * proxy of the real clock on its date. Central to deterministic replay:
     * the time machine merges all clock-driving gateways into one ordered
     * stream.
     *
     * @return whether this gateway drives the clock
     */
    boolean drivesClock();

    /**
     * Declare whether this gateway drives the clock.
     *
     * @param drivesClock true if this gateway should drive the clock
     */
    void setDriveClock(boolean drivesClock);

    // ------------------------------------------------------------------
    // Connection lifecycle
    // ------------------------------------------------------------------

    /** @return whether the gateway is connected to all its sources. */
    boolean connected();

    /** Disconnect the gateway from its sources and stop accepting events. */
    void disconnect();

    // ------------------------------------------------------------------
    // Identity and status
    // ------------------------------------------------------------------

    /** @return the human-readable name of this gateway instance. */
    String name();

    /** @return the current {@link GatewayStatus}. */
    GatewayStatus status();
}
