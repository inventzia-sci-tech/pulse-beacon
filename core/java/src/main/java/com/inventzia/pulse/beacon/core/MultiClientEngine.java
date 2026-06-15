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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Concrete engine that drives multiple actors subscribed to one or more topics.
 *
 * <p>Actors are registered explicitly via {@link #registerActor} with their
 * topic subscriptions and publications declared at registration time. The engine
 * builds an internal routing table — {@code (topic, key) → Set<Actor>} — and
 * fans out each dispatched event to all matching actors.
 *
 * <h2>Actor priority</h2>
 * <p>Actors may be registered as <em>high-priority</em> via
 * {@link #registerActor(Actor, Map, Map, boolean)}. High-priority actors
 * receive each event before normal-priority actors. This is useful for
 * exchange simulators or other infrastructure actors that must react to
 * market data before trading actors do.
 *
 * <h2>Runnable actors</h2>
 * <p>An actor that also implements {@link Runnable} is run on a dedicated
 * daemon thread, started at start-up and interrupted at shutdown.
 *
 * <h2>Actor lifecycle and publishing</h2>
 * <p>Actors extending {@link AbstractActor} are bound to this engine at
 * registration (so {@code publish} forwards here) and receive
 * {@link AbstractActor#onStartUp} / {@link AbstractActor#onShutDown} callbacks
 * around the run. Actors implementing the bare {@link Actor} interface still
 * work but get neither binding nor lifecycle callbacks.
 */
public final class MultiClientEngine extends AbstractEngine {

    // ------------------------------------------------------------------
    // Actor subscription table: (topic, key) → ordered sets of actors
    // ------------------------------------------------------------------

    /**
     * Routing table mapping {@code (topic, key)} to the set of actors
     * subscribed to receive that event.
     */
    private final Map<Topic<?>, Map<String, Set<Actor>>> subscriptions = new HashMap<>();

    /** Registered actors in registration order. */
    private final List<ActorRegistration> actors = new ArrayList<>();

    // ------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------

    public MultiClientEngine(String name, long startTime, long endTime) {
        super(name, startTime, endTime);
    }

    // ------------------------------------------------------------------
    // Actor registration
    // ------------------------------------------------------------------

    /**
     * Registers an actor with the engine at normal priority.
     *
     * @param actor         the actor to register
     * @param subscriptions topics and keys the actor wishes to receive;
     *                      map from topic to the list of key values
     * @param publications  topics and keys the actor intends to publish;
     *                      used for documentation and future routing — may be empty
     */
    public void registerActor(Actor actor,
                              Map<Topic<?>, List<String>> subscriptions,
                              Map<Topic<?>, List<String>> publications) {
        registerActor(actor, subscriptions, publications, false);
    }

    /**
     * Registers an actor with the engine.
     *
     * @param actor         the actor to register
     * @param subscriptions topics and keys the actor wishes to receive
     * @param publications  topics and keys the actor intends to publish
     * @param highPriority  if {@code true}, this actor receives events before
     *                      normal-priority actors
     * @throws IllegalArgumentException if both subscriptions and publications are empty
     */
    public void registerActor(Actor actor,
                              Map<Topic<?>, List<String>> subscriptions,
                              Map<Topic<?>, List<String>> publications,
                              boolean highPriority) {
        Objects.requireNonNull(actor, "actor must not be null");
        if (subscriptions.isEmpty() && publications.isEmpty()) {
            throw new IllegalArgumentException(
                    "Actor must subscribe or publish at least one topic");
        }

        actors.add(new ActorRegistration(actor, highPriority));

        // Bind this engine as the actor's outbound bus so it can publish
        if (actor instanceof AbstractActor base) {
            base.bind(this);
        }

        // Build the (topic, key) → actor routing table
        subscriptions.forEach((topic, keys) ->
            keys.forEach(key ->
                this.subscriptions
                    .computeIfAbsent(topic, t -> new HashMap<>())
                    .computeIfAbsent(key,   k -> new HashSet<>())
                    .add(actor)
            )
        );
    }

    // ------------------------------------------------------------------
    // AbstractEngine — actor lifecycle
    // ------------------------------------------------------------------

    @Override
    protected void startUpActors(long time) {
        for (ActorRegistration reg : actors) {
            // Start own-thread actors first, so they are running when notified
            if (reg.actor() instanceof Runnable runnable && reg.ownThread()) {
                Thread t = new Thread(runnable, "actor-" + reg.actor().getClass().getSimpleName());
                t.setDaemon(true);
                t.start();
                reg.setThread(t);
            }
            // Then deliver the start-up lifecycle callback
            if (reg.actor() instanceof AbstractActor base) {
                base.onStartUp(time);
            }
        }
    }

    @Override
    protected void shutDownActors(long time) {
        for (ActorRegistration reg : actors) {
            if (reg.actor() instanceof AbstractActor base) {
                base.onShutDown(time);
            }
            Thread t = reg.thread();
            if (t != null && t.isAlive()) {
                t.interrupt();
            }
        }
    }

    @Override
    protected boolean actorsReadyToShutDown() {
        return actors.stream()
                     .filter(ActorRegistration::ownThread)
                     .allMatch(r -> r.thread() == null || !r.thread().isAlive());
    }

    // ------------------------------------------------------------------
    // AbstractEngine — dispatch
    // ------------------------------------------------------------------

    /**
     * Fans out the event carried in {@code te} to all actors subscribed to
     * its topic and key. High-priority actors are dispatched first.
     */
    @Override
    protected void timeEventToActors(TimeEvent te) {
        Map<String, Set<Actor>> keyMap = subscriptions.get(te.topic());
        if (keyMap == null) return;
        Set<Actor> matched = keyMap.get(te.key());
        if (matched == null || matched.isEmpty()) return;

        boolean hasHighPriority = actors.stream()
                .anyMatch(r -> r.highPriority() && matched.contains(r.actor()));

        if (hasHighPriority) {
            // High-priority actors first
            for (ActorRegistration reg : actors) {
                if (reg.highPriority() && matched.contains(reg.actor())) {
                    dispatchTyped(te, reg.actor());
                }
            }
            // Then normal-priority actors
            for (ActorRegistration reg : actors) {
                if (!reg.highPriority() && matched.contains(reg.actor())) {
                    dispatchTyped(te, reg.actor());
                }
            }
        } else {
            for (Actor actor : matched) {
                dispatchTyped(te, actor);
            }
        }
    }

    // ------------------------------------------------------------------
    // Actor registration record
    // ------------------------------------------------------------------

    private static final class ActorRegistration {
        private final Actor   actor;
        private final boolean highPriority;
        private final boolean ownThread;
        private volatile Thread thread;

        ActorRegistration(Actor actor, boolean highPriority) {
            this.actor        = actor;
            this.highPriority = highPriority;
            this.ownThread    = actor instanceof Runnable;
        }

        Actor   actor()       { return actor; }
        boolean highPriority(){ return highPriority; }
        boolean ownThread()   { return ownThread; }
        Thread  thread()      { return thread; }
        void    setThread(Thread t) { this.thread = t; }
    }
}
