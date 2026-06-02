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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Base implementation of {@link Gateway}.
 *
 * <p>Provides the common machinery shared by all gateway implementations:
 * publisher/subscriber routing maps, the lifecycle state machine, operating
 * mode determination, and optional out-of-band reporting. Concrete gateways
 * extend this class and implement {@link #run()}, {@link Pub#publish}, and
 * {@link Sub#onEvent}.
 *
 * <h2>Lifecycle</h2>
 * <pre>
 *   constructor → initialize() → connect() → [STARTED] → disconnect() → [STOPPED]
 * </pre>
 * Subclasses override {@link #initialize()} and {@link #connect()}, calling
 * {@code super} first, then performing their own setup. Status transitions
 * beyond {@code PRESTART} (e.g. to {@code STARTED}) are the subclass's
 * responsibility.
 *
 * <h2>Routing maps</h2>
 * <p>The gateway maintains two one-to-one routing tables:
 * <ul>
 *   <li><em>subscribers</em> — for each {@code (topic, key)} this gateway
 *       publishes, the single downstream {@link Gateway} that receives it.</li>
 *   <li><em>publishers</em> — for each {@code (topic, key)} this gateway
 *       subscribes to, the single upstream {@link Gateway} that sends it.</li>
 * </ul>
 * Registration is mutual: calling {@link #registerPublisher} on this gateway
 * also registers this gateway as a subscriber on the publisher, and vice
 * versa. A circular-registration guard prevents infinite recursion.
 *
 * <h2>Reporting</h2>
 * <p>An optional {@link Reporter} can be supplied via
 * {@link #setReporter(Reporter)}. Only events at or above the configured
 * {@link ReportLevel} threshold are forwarded. Application logging uses
 * SLF4J directly in concrete subclasses; the {@code Reporter} is reserved
 * for operator-facing alerts.
 */
public abstract class AbstractGateway implements Gateway {

    // ------------------------------------------------------------------
    // Routing table — one-to-one (topic, key) ↔ Gateway, both directions
    // ------------------------------------------------------------------

    private static final class RoutingTable {

        /** Forward: (topic, key) → the registered gateway. */
        private final Map<Topic<?>, Map<String, Gateway>> byTopicKey = new HashMap<>();
        /** Reverse: gateway → its registered (topic → keys). Needed for disconnect walk. */
        private final Map<Gateway, Map<Topic<?>, Set<String>>> byGateway = new HashMap<>();

        synchronized void add(Topic<?> topic, String key, Gateway gateway) {
            byTopicKey.computeIfAbsent(topic,   t -> new HashMap<>()).put(key, gateway);
            byGateway .computeIfAbsent(gateway, g -> new HashMap<>())
                      .computeIfAbsent(topic,   t -> new HashSet<>())
                      .add(key);
        }

        synchronized void remove(Topic<?> topic, String key) {
            Map<String, Gateway> keyMap = byTopicKey.get(topic);
            if (keyMap == null) return;
            Gateway gw = keyMap.remove(key);
            if (keyMap.isEmpty()) byTopicKey.remove(topic);
            if (gw == null) return;
            Map<Topic<?>, Set<String>> topicMap = byGateway.get(gw);
            if (topicMap == null) return;
            Set<String> keys = topicMap.get(topic);
            if (keys != null) {
                keys.remove(key);
                if (keys.isEmpty()) topicMap.remove(topic);
            }
            if (topicMap.isEmpty()) byGateway.remove(gw);
        }

        synchronized Gateway get(Topic<?> topic, String key) {
            Map<String, Gateway> m = byTopicKey.get(topic);
            return m == null ? null : m.get(key);
        }

        /**
         * Returns a snapshot of the topic→keys registration for the given
         * gateway, or {@code null} if it is not in this table.
         * The snapshot is a defensive copy safe to iterate while the table
         * is modified concurrently.
         */
        synchronized Map<Topic<?>, List<String>> snapshotFor(Gateway gw) {
            Map<Topic<?>, Set<String>> src = byGateway.get(gw);
            if (src == null) return null;
            Map<Topic<?>, List<String>> copy = new HashMap<>();
            src.forEach((t, ks) -> copy.put(t, new ArrayList<>(ks)));
            return copy;
        }

        synchronized Set<Gateway> gateways() { return new HashSet<>(byGateway.keySet()); }

        synchronized boolean isEmpty() { return byGateway.isEmpty(); }
    }

    // ------------------------------------------------------------------
    // Fields
    // ------------------------------------------------------------------

    private final String           name;
    private volatile GatewayStatus status        = GatewayStatus.BLANK;
    private boolean                drivesClock   = false;
    private long                   startTime;
    private long                   endTime;
    private OperatingMode          operatingMode = OperatingMode.UNDEFINED;

    /** Who receives events published by this gateway: (topic, key) → subscriber. */
    private final RoutingTable subscribers = new RoutingTable();
    /** Who publishes events to this gateway: (topic, key) → publisher. */
    private final RoutingTable publishers  = new RoutingTable();

    private boolean autodisconnectWhenNoMorePublishers = false;
    private boolean unregisterAllOnDisconnect          = true;

    private Reporter    reporter          = null;
    private ReportLevel reporterThreshold = ReportLevel.WARNING;

    // ------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------

    /**
     * @param name      human-readable name for this gateway instance
     * @param startTime epoch millis for the start of this gateway's window
     * @param endTime   epoch millis for the end of this gateway's window
     */
    protected AbstractGateway(String name, long startTime, long endTime) {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) throw new IllegalArgumentException("name must not be blank");
        this.name      = name;
        this.startTime = startTime;
        this.endTime   = endTime;
    }

    // ------------------------------------------------------------------
    // Gateway — identity and status
    // ------------------------------------------------------------------

    @Override public String        name()   { return name; }
    @Override public GatewayStatus status() { return status; }

    // ------------------------------------------------------------------
    // Gateway — simulation window
    // ------------------------------------------------------------------

    @Override
    public void setStartEnd(long startTime, long endTime) {
        this.startTime = startTime;
        this.endTime   = endTime;
    }

    // ------------------------------------------------------------------
    // Gateway — clock driving
    // ------------------------------------------------------------------

    @Override public boolean drivesClock()            { return drivesClock; }
    @Override public void    setDriveClock(boolean d) { this.drivesClock = d; }

    // ------------------------------------------------------------------
    // Gateway — connection lifecycle
    // ------------------------------------------------------------------

    @Override
    public boolean connected() {
        return switch (status) {
            case PRESTART, STARTED, WRAP_UP, FINALIZE -> true;
            default -> false;
        };
    }

    /**
     * Transitions the gateway toward shutdown.
     *
     * <p>If the current status is below {@code WRAP_UP}, advances to
     * {@code WRAP_UP}. If {@link #unregisterAllOnDisconnect} is set,
     * notifies each registered subscriber that this gateway is no longer
     * publishing by calling {@link Gateway#unregisterPublisher} on it.
     *
     * <p>Subclasses that override this method should call {@code super.disconnect()}
     * and then perform their own teardown (closing sockets, stopping threads,
     * etc.) before setting status to {@code STOPPED}.
     */
    @Override
    public void disconnect() {
        if (status.ordinal() < GatewayStatus.WRAP_UP.ordinal()) {
            setStatus(GatewayStatus.WRAP_UP);
        }
        if (unregisterAllOnDisconnect) {
            for (Gateway sub : subscribers.gateways()) {
                Map<Topic<?>, List<String>> snapshot = subscribers.snapshotFor(sub);
                if (snapshot == null) continue;
                snapshot.forEach((topic, keys) -> sub.unregisterPublisher(this, topic, keys));
            }
        }
    }

    // ------------------------------------------------------------------
    // Gateway — registration
    // ------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Registration is mutual: this method also calls
     * {@link Gateway#registerPublisher registerPublisher(this, topic, keys)}
     * on the subscriber, unless the subscriber is this gateway itself. A
     * circular-registration guard prevents re-entrant calls from recursing
     * further.
     */
    @Override
    public synchronized void registerSubscriber(Gateway subscriber, Topic<?> topic, List<String> keys) {
        for (String key : keys) {
            if (subscribers.get(topic, key) == subscriber) return; // circular guard
        }
        keys.forEach(key -> subscribers.add(topic, key, subscriber));
        if (subscriber != this) {
            subscriber.registerPublisher(this, topic, keys);
        }
    }

    @Override
    public synchronized void unregisterSubscriber(Gateway subscriber, Topic<?> topic, List<String> keys) {
        keys.forEach(key -> subscribers.remove(topic, key));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Registration is mutual: this method also calls
     * {@link Gateway#registerSubscriber registerSubscriber(this, topic, keys)}
     * on the publisher, unless the publisher is this gateway itself. A
     * circular-registration guard prevents re-entrant calls from recursing
     * further.
     */
    @Override
    public synchronized void registerPublisher(Gateway publisher, Topic<?> topic, List<String> keys) {
        for (String key : keys) {
            if (publishers.get(topic, key) == publisher) return; // circular guard
        }
        keys.forEach(key -> publishers.add(topic, key, publisher));
        if (publisher != this) {
            publisher.registerSubscriber(this, topic, keys);
        }
    }

    @Override
    public synchronized void unregisterPublisher(Gateway publisher, Topic<?> topic, List<String> keys) {
        keys.forEach(key -> publishers.remove(topic, key));
        if (autodisconnectWhenNoMorePublishers && publishers.isEmpty()) {
            disconnect();
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle hooks
    // ------------------------------------------------------------------

    /**
     * Determines the {@link OperatingMode} from the configured time window
     * and transitions to {@link GatewayStatus#INITIALIZED}.
     *
     * <p>Subclasses should call {@code super.initialize()} first, then
     * perform their own initialisation (e.g. opening connections).
     */
    protected void initialize() {
        long now = System.currentTimeMillis();
        operatingMode = (now > endTime)   ? OperatingMode.COMPRESSED_TIME :
                        (now >= startTime) ? OperatingMode.MIXED           :
                                             OperatingMode.REAL_TIME;
        setStatus(GatewayStatus.INITIALIZED);
    }

    /**
     * Transitions to {@link GatewayStatus#PRESTART}.
     *
     * <p>Subclasses should call {@code super.connect()} first, then
     * complete their connection work and call
     * {@link #setStatus(GatewayStatus) setStatus(STARTED)}.
     */
    protected void connect() {
        setStatus(GatewayStatus.PRESTART);
    }

    /** @return {@code true} if this gateway has reached {@link GatewayStatus#STOPPED}. */
    protected boolean stopped() {
        return status == GatewayStatus.STOPPED;
    }

    // ------------------------------------------------------------------
    // Protected helpers
    // ------------------------------------------------------------------

    /** Transitions to {@code newStatus} and reports the change at {@code INFO} level. */
    protected void setStatus(GatewayStatus newStatus) {
        this.status = newStatus;
        report("Status → " + newStatus, ReportLevel.INFO);
    }

    /** @return the operating mode determined during {@link #initialize()}. */
    protected OperatingMode operatingMode() { return operatingMode; }

    protected long startTime() { return startTime; }
    protected long endTime()   { return endTime; }

    /**
     * Returns the gateway registered to receive events from this gateway on
     * the given {@code topic} and {@code key}, or {@code null} if none.
     *
     * <p>Subclass {@link Pub#publish} implementations use this to look up
     * the downstream routing target before calling
     * {@link Sub#onEvent onEvent} on it.
     */
    protected Gateway subscriberForKey(Topic<?> topic, String key) {
        return subscribers.get(topic, key);
    }

    /**
     * Returns the gateway registered to publish to this gateway on the given
     * {@code topic} and {@code key}, or {@code null} if none is registered.
     *
     * <p>Used by engine implementations to recover the originating gateway
     * inside {@link Sub#onEvent} so it can be passed to the {@link TimeMachine}.
     */
    protected Gateway publisherForKey(Topic<?> topic, String key) {
        return publishers.get(topic, key);
    }

    /**
     * Returns a snapshot of all gateways currently registered as subscribers
     * of this gateway (those that receive events it publishes).
     */
    protected Set<Gateway> registeredSubscribers() {
        return subscribers.gateways();
    }

    /**
     * Returns a snapshot of all gateways currently registered as publishers
     * to this gateway (those that send events to it).
     */
    protected Set<Gateway> registeredPublishers() {
        return publishers.gateways();
    }

    /** Sets the out-of-band {@link Reporter}. Pass {@code null} to disable reporting. */
    protected void setReporter(Reporter reporter) {
        this.reporter = reporter;
    }

    /**
     * Sets the minimum {@link ReportLevel} forwarded to the {@link Reporter}.
     * Events below this threshold are suppressed. Default: {@link ReportLevel#WARNING}.
     */
    protected void setReporterThreshold(ReportLevel threshold) {
        this.reporterThreshold = Objects.requireNonNull(threshold);
    }

    /**
     * If {@code true}, this gateway will call {@link #disconnect()} automatically
     * when its last publisher unregisters. Default: {@code false}.
     */
    protected void setAutodisconnectWhenNoMorePublishers(boolean value) {
        this.autodisconnectWhenNoMorePublishers = value;
    }

    /**
     * If {@code true} (the default), {@link #disconnect()} notifies all
     * registered subscribers that this gateway is stopping by calling
     * {@link Gateway#unregisterPublisher unregisterPublisher} on each of them.
     */
    protected void setUnregisterAllOnDisconnect(boolean value) {
        this.unregisterAllOnDisconnect = value;
    }

    /**
     * Forwards {@code message} to the {@link Reporter} if one is configured
     * and {@code level} meets the threshold.
     */
    protected void report(String message, ReportLevel level) {
        if (reporter != null && level.meets(reporterThreshold)) {
            reporter.report(System.currentTimeMillis(), name, message, level);
        }
    }
}
