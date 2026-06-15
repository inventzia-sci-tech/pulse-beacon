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
package com.inventzia.pulse.beacon.core;

import com.inventzia.pulse.data.datum.Datum;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Merges events from multiple {@link Gateway}s into a single, deterministically
 * ordered stream.
 *
 * <h2>Clock-driving gateways and determinism</h2>
 * <p>Some gateways are designated as <em>clock drivers</em> via
 * {@link Gateway#drivesClock()}. These are the authoritative time sources —
 * typically file or socket gateways that replay historical data or forward
 * live market events. The TimeMachine enforces the following invariant:
 *
 * <blockquote>
 * The consumer ({@link #take}) does not dispatch the next event until every
 * registered clock-driving gateway has contributed at least one event to the
 * queue, establishing a consistent time horizon.
 * </blockquote>
 *
 * <p>This guarantees that events are processed in causal order regardless of
 * thread scheduling or I/O jitter. It is the property that makes a backtest
 * reproducible and a live run equivalent to its replay.
 *
 * <h2>Per-gateway flow control</h2>
 * <p>Each clock-driving gateway is issued a single write permit (a binary
 * {@link Semaphore}). A gateway may not enqueue its next event until its
 * previous event has been dispatched and acknowledged via {@link #done}.
 * This prevents a fast gateway from flooding the queue and starving slower
 * ones.
 *
 * <h2>Event ordering</h2>
 * <p>Events are ordered by {@link Event#eventTime()} (the wall-clock time the
 * event represents), with {@link TimeEvent#beginTstamp()} as tiebreaker.
 * This is semantically correct for causal replay: events are processed in the
 * order they occurred, not the order they arrived.
 *
 * <h2>Thread model</h2>
 * <p>Designed for <em>multiple producer threads</em> (one per clock-driving
 * gateway) and a <em>single consumer thread</em> (the engine's dispatch loop).
 * All queue mutations are guarded by {@code synchronized(this)}; blocking
 * waits use {@link Semaphore} rather than spinning.
 */
public final class TimeMachine {

    private static final TimeEventComparator ORDER = new TimeEventComparator();

    private final ComponentReporter log;

    /**
     * @param source the logger name this time machine reports under
     *               (typically the owning engine's name + {@code ".timemachine"})
     */
    public TimeMachine(String source) {
        this.log = new ComponentReporter(source, Slf4jReporter.shared());
    }

    // ------------------------------------------------------------------
    // Queue — sorted ascending by eventTime, tiebreak by beginTstamp
    // ------------------------------------------------------------------
    private final List<TimeEvent> queue = new ArrayList<>();

    // ------------------------------------------------------------------
    // Clock-driver tracking
    // ------------------------------------------------------------------

    /** All registered clock-driving gateways. */
    private final Set<Gateway> drivers = ConcurrentHashMap.newKeySet();

    /**
     * Clock-driving gateways that currently have at least one event in the
     * queue. The consumer may only dispatch when this set equals {@link #drivers}.
     */
    private final Set<Gateway> pending = ConcurrentHashMap.newKeySet();

    // ------------------------------------------------------------------
    // Synchronization
    // ------------------------------------------------------------------

    /**
     * Released (permit count → 1) when the queue is ready to dispatch;
     * drained to 0 when not ready. The consumer blocks here.
     */
    private final Semaphore consumerReady = new Semaphore(0);

    /**
     * Per clock-driving gateway: starts at 1 (free to enqueue).
     * Taken by {@link #addEvent} and released by {@link #done}.
     * A gateway blocks in {@link #addEvent} if its previous event has not
     * yet been dispatched.
     */
    private final Map<Gateway, Semaphore> permits = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------
    // Registration
    // ------------------------------------------------------------------

    /**
     * Registers a clock-driving gateway with the time machine.
     * Non-clock-driving gateways are silently ignored.
     *
     * <p>Must be called before the gateway begins producing events.
     *
     * @param gateway the gateway to register
     */
    public void registerGateway(Gateway gateway) {
        if (!gateway.drivesClock()) return;
        permits.computeIfAbsent(gateway, g -> new Semaphore(1, true));
        drivers.add(gateway);
        log.largeInfo("registered clock-driving gateway " + gateway.name());
    }

    /**
     * Removes a clock-driving gateway from the clock-driver set, so the
     * consumer no longer waits for it. Non-clock-driving gateways are silently
     * ignored.
     *
     * <p>Events the gateway has <em>already</em> enqueued are <strong>kept</strong>
     * and dispatched in time order — a gateway typically disconnects right after
     * enqueuing its final event, before that event has been consumed, so
     * discarding them here would non-deterministically drop the gateway's last
     * event. Dropping the gateway from {@link #drivers} is sufficient: the
     * consumer stops requiring it to be pending, and its remaining events drain
     * naturally.
     *
     * <p>Releases the gateway's write permit so any thread still blocked in
     * {@link #addEvent} for this gateway can unblock and observe that the
     * gateway is no longer connected.
     *
     * @param gateway the gateway to remove
     */
    public void removeGateway(Gateway gateway) {
        if (!gateway.drivesClock()) return;
        synchronized (this) {
            drivers.remove(gateway);
            pending.remove(gateway);
            signalConsumer();
        }
        Semaphore permit = permits.remove(gateway);
        if (permit != null) permit.release();
        log.largeInfo("removed clock-driving gateway " + gateway.name());
    }

    // ------------------------------------------------------------------
    // Producer path
    // ------------------------------------------------------------------

    /**
     * Enqueues an event from {@code origin} in time order.
     *
     * <p>For clock-driving gateways this method blocks until the gateway's
     * previous event has been acknowledged via {@link #done}, ensuring
     * at most one pending event per clock driver at any time.
     *
     * <p>If {@code origin} is no longer {@link Gateway#connected()} when this
     * method acquires the write permit, the event is silently dropped and the
     * permit is released.
     *
     * @param payload the payload to enqueue
     * @param topic   the topic the payload was published on
     * @param origin  the gateway that produced the payload
     * @throws InterruptedException if the thread is interrupted while waiting
     *                              for the per-gateway write permit
     */
    public void addEvent(Datum payload, Topic<?> topic, Gateway origin)
            throws InterruptedException {

        boolean driver = origin.drivesClock();
        Semaphore permit = driver ? permits.get(origin) : null;

        if (permit != null) {
            // Blocks if the previous event from this gateway has not yet been
            // dispatched. Releases write pressure from slower consumers.
            permit.acquire();
        }

        synchronized (this) {
            if (!origin.connected()) {
                log.warn("dropping event from disconnected gateway " + origin.name()
                        + " on " + topic.name());
                if (permit != null) permit.release();
                return;
            }
            long now = System.currentTimeMillis();
            TimeEvent te = new TimeEvent(payload, topic, origin, now, now);
            insertSorted(te);
            if (driver) pending.add(origin);
            log.largeInfo(() -> "queued " + topic.name() + " @ " + payload.getDatumTime()
                    + " from " + origin.name());
            signalConsumer();
        }
    }

    // ------------------------------------------------------------------
    // Consumer path
    // ------------------------------------------------------------------

    /**
     * Retrieves and removes the head of the queue, blocking until the queue
     * is ready to dispatch.
     *
     * <p>The queue is considered ready when every registered clock-driving
     * gateway has at least one pending event, or when no clock-driving
     * gateways are registered.
     *
     * <p>After processing the returned event, the caller <strong>must</strong>
     * call {@link #done(TimeEvent)} to release the originating gateway's write
     * permit and allow it to enqueue its next event.
     *
     * @return the next {@link TimeEvent} in time order, or {@code null} if
     *         the queue was cleared during shutdown
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public TimeEvent take() throws InterruptedException {
        consumerReady.acquire();
        synchronized (this) {
            if (queue.isEmpty()) return null; // cleared during shutdown
            TimeEvent te = queue.remove(0);
            if (te.origin().drivesClock()) {
                pending.remove(te.origin());
            }
            signalConsumer();
            return te;
        }
    }

    /**
     * Acknowledges that {@code te} has been fully dispatched to all
     * subscribers.
     *
     * <p>For clock-driving gateways, this releases the per-gateway write
     * permit so the gateway can enqueue its next event.
     *
     * @param te the event returned by a previous call to {@link #take()}
     */
    public void done(TimeEvent te) {
        Semaphore permit = permits.get(te.origin());
        if (permit != null) permit.release();
    }

    // ------------------------------------------------------------------
    // Utilities
    // ------------------------------------------------------------------

    /** Returns the number of events currently in the queue. */
    public synchronized int size() {
        return queue.size();
    }

    /**
     * Clears the queue and unblocks any waiting consumer.
     * Intended for engine shutdown sequences.
     */
    public synchronized void clear() {
        queue.clear();
        pending.clear();
        signalConsumer();
    }

    // ------------------------------------------------------------------
    // Internal
    // ------------------------------------------------------------------

    /**
     * Inserts {@code te} into {@link #queue} at the position that preserves
     * ascending time order. Uses binary search: O(log n) comparisons,
     * O(n) list shift. Queue size is bounded by the number of registered
     * gateways so n is small in practice.
     *
     * <p>Must be called while holding {@code synchronized(this)}.
     */
    private void insertSorted(TimeEvent te) {
        int lo = 0, hi = queue.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (ORDER.compare(queue.get(mid), te) <= 0) lo = mid + 1;
            else hi = mid;
        }
        queue.add(lo, te);
    }

    /**
     * Updates {@link #consumerReady} to reflect whether the queue is ready
     * to dispatch.
     *
     * <p>Ready when: the queue is non-empty AND (no clock drivers registered
     * OR every registered clock driver has a pending event).
     *
     * <p>Must be called while holding {@code synchronized(this)}.
     */
    private void signalConsumer() {
        boolean allDriversReady = pending.containsAll(drivers);
        boolean ready = !queue.isEmpty() && (drivers.isEmpty() || allDriversReady);
        if (ready && consumerReady.availablePermits() == 0) {
            consumerReady.release();
        } else if (!ready) {
            consumerReady.drainPermits();
        }
    }
}
