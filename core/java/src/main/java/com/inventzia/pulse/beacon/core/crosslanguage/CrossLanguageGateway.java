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

import com.inventzia.pulse.beacon.core.AbstractGateway;
import com.inventzia.pulse.beacon.core.Gateway;
import com.inventzia.pulse.beacon.core.GatewayStatus;
import com.inventzia.pulse.beacon.core.Topic;
import com.inventzia.pulse.data.datum.Datum;
import com.inventzia.pulse.data.datum.DatumCodec;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;

/**
 * The Java half of a gateway (external boundary) implemented in another
 * language. Mirrors the old bidirectional {@code PythonGateway}: it can be a
 * <b>source</b> (an external feed injecting data into the run) and/or a
 * <b>sink</b> (forwarding run events out to an external system).
 *
 * <h2>Source role (clock-driving)</h2>
 * <p>Structurally identical to {@code HeartBeatGateway.run()} — its own thread
 * pulls the next datum from the foreign side and emits it via
 * {@code downstream.onEvent}, which in compressed time blocks on the
 * {@code TimeMachine} write permit until the previous event is consumed. The
 * foreign side's {@link #offerNext} blocks until that emission completes, so a
 * Python feed inherits deterministic event-time merging for free. Exhausting
 * the source ({@link #finish}) disconnects, signalling the engine the clock
 * source is done.
 *
 * <h2>Sink role</h2>
 * <p>When the engine dispatches to this gateway as a subscriber, {@link #onEvent}
 * hands the event across the boundary and blocks until acked — the same
 * handshake as {@link CrossLanguageActor}.
 *
 * <p>A gateway may play both roles at once: the source loop runs on this
 * gateway's thread while the sink is driven by the engine's dispatch thread,
 * each with its own handshake — the modern form of the old
 * {@code run_outgoing_data} / {@code run_incoming_data} thread pair.
 */
public final class CrossLanguageGateway extends AbstractGateway {

    /** name → Topic, for topics this gateway produces on or consumes from. */
    private final Map<String, Topic<?>> topics = new ConcurrentHashMap<>();

    // -- SOURCE: foreign language → engine, permit-paced --
    private final SynchronousQueue<CrossLanguageEvent> outbound = new SynchronousQueue<>();
    private final Semaphore accepted = new Semaphore(0);

    // -- SINK: engine → foreign language --
    private final SynchronousQueue<CrossLanguageEvent> inbound = new SynchronousQueue<>();
    private final Semaphore done = new Semaphore(0);

    /** Lets a sink-only run() idle until disconnected, without polling. */
    private final CountDownLatch stopLatch = new CountDownLatch(1);

    /**
     * @param name      human-readable name for this gateway instance
     * @param startTime epoch millis for the start of this gateway's window
     * @param endTime   epoch millis for the end of this gateway's window
     */
    public CrossLanguageGateway(String name, long startTime, long endTime) {
        super(name, startTime, endTime);
        setDriveClock(true); // default: an external source drives the clock
    }

    /** Registers a topic this gateway produces on or consumes from. Returns {@code this}. */
    public CrossLanguageGateway withTopic(Topic<?> topic) {
        Objects.requireNonNull(topic, "topic");
        topics.put(topic.name(), topic);
        return this;
    }

    // ------------------------------------------------------------------
    // Source — foreign language → engine
    // ------------------------------------------------------------------

    /**
     * Offers the foreign side's next datum. Blocks until it has been emitted
     * into the engine through the time-machine write permit (i.e. paced to the
     * event-time merge), so the foreign producer cannot run ahead.
     *
     * @param topicName  the (registered) topic name to emit on
     * @param taggedJson the datum as a self-describing tagged envelope
     */
    public void offerNext(String topicName, String taggedJson) {
        try {
            outbound.put(CrossLanguageEvent.data(topicName, taggedJson));
            accepted.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CrossLanguageException(name() + " interrupted offering an event", e);
        }
    }

    /** Signals that the foreign source is exhausted; ends the source loop. */
    public void finish() {
        try {
            outbound.put(CrossLanguageEvent.END);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void run() {
        initialize();
        connect();
        setStatus(GatewayStatus.STARTED);
        try {
            if (drivesClock()) {
                runSource();
            } else {
                stopLatch.await(); // sink-only: idle until disconnected
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        disconnect();
        setStatus(GatewayStatus.STOPPED);
    }

    private void runSource() throws InterruptedException {
        while (connected()) {
            CrossLanguageEvent event = outbound.take();
            if (event.kind() == CrossLanguageEvent.Kind.END) {
                break;
            }
            try {
                Datum datum = DatumCodec.instance().fromTaggedJson(event.taggedJson());
                Topic<?> topic = topics.get(event.topicName());
                if (topic == null) {
                    throw new CrossLanguageException(
                            name() + ": no registered topic '" + event.topicName() + "'");
                }
                // D4: routing key comes from the datum itself (single source of truth).
                Gateway downstream = subscriberForKey(topic, datum.getDatumKey());
                if (downstream != null) {
                    // Blocks on the time-machine write permit until the prior event is consumed.
                    deliver(downstream, topic, datum);
                } else {
                    log.warn("no subscriber for " + topic.name() + " key=" + datum.getDatumKey());
                }
            } finally {
                accepted.release(); // let the foreign side produce the next, even on error
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <P extends Datum> void deliver(Gateway downstream, Topic<?> topic, Datum datum) {
        Topic<P> typed = (Topic<P>) topic;
        downstream.onEvent(typed, typed.payloadType().cast(datum));
    }

    @Override
    public void disconnect() {
        stopLatch.countDown();
        super.disconnect();
    }

    // ------------------------------------------------------------------
    // Sink — engine → foreign language (blocks until acked)
    // ------------------------------------------------------------------

    @Override
    public <P extends Datum> void onEvent(Topic<P> topic, P payload) {
        try {
            inbound.put(CrossLanguageEvent.data(topic.name(), DatumCodec.instance().toTaggedJson(payload)));
            done.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CrossLanguageException(name() + " interrupted handing event to the foreign side", e);
        }
    }

    /** Blocks for the next event to forward out. Returns {@link CrossLanguageEvent.Kind#END} to stop. */
    public CrossLanguageEvent takeNext() throws InterruptedException {
        return inbound.take();
    }

    /** Acknowledges that the last taken event has been forwarded, releasing the engine. */
    public void ackDone() {
        done.release();
    }

    // ------------------------------------------------------------------
    // Pub — gateways emit via downstream.onEvent (run loop), not their own publish()
    // ------------------------------------------------------------------

    @Override
    public <P extends Datum> void publish(Topic<P> topic, P payload) {
        throw new UnsupportedOperationException(
                name() + ": CrossLanguageGateway emits via its source loop, not publish()");
    }
}
