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

import com.inventzia.pulse.beacon.core.AbstractActor;
import com.inventzia.pulse.beacon.core.Topic;
import com.inventzia.pulse.data.datum.Datum;
import com.inventzia.pulse.data.datum.DatumCodec;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;

/**
 * The Java half of a consumer/strategy actor implemented in another language.
 *
 * <p>The engine dispatches events to this actor like any other; instead of
 * computing in Java, it hands each event across the language boundary and
 * blocks until the foreign-language streamer has processed it. That blocking is
 * the determinism handshake: in compressed time the engine's dispatch thread
 * runs {@link #onEvent} synchronously and only advances the clock afterwards, so
 * pausing here until the Python side acks keeps replay deterministic — no
 * separate "done consuming" signal is needed.
 *
 * <h2>Threads and handshake</h2>
 * <ul>
 *   <li>The <b>engine dispatch thread</b> calls {@link #onEvent} /
 *       {@link #onStartUp} / {@link #onShutDown}: each puts one
 *       {@link CrossLanguageEvent} on {@link #inbound} and blocks on
 *       {@link #done} until acked.</li>
 *   <li>The <b>foreign-language streamer thread</b> drains via {@link #takeNext}
 *       and releases the engine with {@link #ackDone} after handling each
 *       event.</li>
 * </ul>
 *
 * <p>Events cross as {@code (topicName, taggedJson)} — a self-describing
 * envelope — so the other language reconstructs a native datum without an
 * ambient topic→type map. Publishing back works the other way:
 * {@link #publishTagged} decodes a tagged envelope and emits it onto the bus.
 *
 * <p>This actor is purely reactive (it does not implement {@link Runnable}), so
 * the engine never gives it its own thread; the streamer owns the foreign side.
 */
public final class CrossLanguageActor extends AbstractActor {

    /** name → Topic, for topics this actor may publish on (decode + routing). */
    private final Map<String, Topic<?>> topics = new ConcurrentHashMap<>();

    // Engine dispatch thread → foreign streamer. Unbounded so the END sentinel
    // can be enqueued without a consumer present (the abort path must not block).
    // The one-event-at-a-time determinism handshake lives in the `done` semaphore,
    // not in the queue: onEvent blocks on `done` until the streamer acks, so the
    // engine never has more than one data event outstanding regardless.
    private final LinkedBlockingQueue<CrossLanguageEvent> inbound = new LinkedBlockingQueue<>();
    /** Released by the streamer once it has processed the handed event. */
    private final Semaphore done = new Semaphore(0);

    public CrossLanguageActor(String name) {
        super(name);
    }

    /**
     * Registers a topic this actor may publish on, so {@link #publishTagged} can
     * map a topic name back to its {@link Topic}. Returns {@code this} for chaining.
     */
    public CrossLanguageActor withTopic(Topic<?> topic) {
        Objects.requireNonNull(topic, "topic");
        topics.put(topic.name(), topic);
        return this;
    }

    // ------------------------------------------------------------------
    // Inbound — engine → foreign language (blocks until acked)
    // ------------------------------------------------------------------

    @Override
    public <P extends Datum> void onEvent(Topic<P> topic, P payload) {
        handToForeign(CrossLanguageEvent.data(topic.name(), DatumCodec.instance().toTaggedJson(payload)));
    }

    @Override
    protected void onStartUp(long timeMillis) {
        log.info("starting up @ " + timeMillis);
        handToForeign(CrossLanguageEvent.start(timeMillis));
    }

    @Override
    protected void onShutDown(long timeMillis) {
        log.info("shutting down @ " + timeMillis);
        handToForeign(CrossLanguageEvent.stop(timeMillis));
        // Terminate the streamer loop once the stop has been acked. Non-blocking:
        // run_consume breaks on END before acking, so there is no ack to wait for.
        inbound.offer(CrossLanguageEvent.END);
    }

    /**
     * Abnormal-termination cleanup: end the streamer loop without the STOP
     * handshake (the run is already failing and the engine must not block on an
     * ack). Non-blocking, idempotent — a duplicate END is harmless.
     */
    @Override
    protected void onAbort() {
        inbound.offer(CrossLanguageEvent.END);
    }

    private void handToForeign(CrossLanguageEvent event) {
        try {
            inbound.put(event);
            done.acquire(); // block the engine thread until the streamer acks
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CrossLanguageException(name() + " interrupted handing event to the foreign side", e);
        }
    }

    // ------------------------------------------------------------------
    // Called by the foreign-language streamer
    // ------------------------------------------------------------------

    /** Blocks for the next event to process. Returns {@link CrossLanguageEvent.Kind#END} to stop. */
    public CrossLanguageEvent takeNext() throws InterruptedException {
        return inbound.take();
    }

    /** Acknowledges that the last taken event has been processed, releasing the engine. */
    public void ackDone() {
        done.release();
    }

    // ------------------------------------------------------------------
    // Outbound — foreign language → bus
    // ------------------------------------------------------------------

    /**
     * Publishes a datum produced by the foreign side onto the bus.
     *
     * @param topicName  the (registered) topic name to publish on
     * @param taggedJson the datum as a self-describing tagged envelope
     */
    public void publishTagged(String topicName, String taggedJson) {
        Topic<?> topic = topics.get(topicName);
        if (topic == null) {
            throw new CrossLanguageException(
                    name() + ": no registered topic '" + topicName + "' to publish on");
        }
        publishTyped(topic, DatumCodec.instance().fromTaggedJson(taggedJson));
    }

    @SuppressWarnings("unchecked")
    private <P extends Datum> void publishTyped(Topic<?> topic, Datum datum) {
        Topic<P> typed = (Topic<P>) topic;
        publish(typed, typed.payloadType().cast(datum));
    }
}
