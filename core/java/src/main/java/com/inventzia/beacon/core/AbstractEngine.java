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

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Base implementation of the pulse-beacon engine.
 *
 * <p>The engine is the central hub of the platform. It extends
 * {@link AbstractGateway} and adds the dispatch loop that drives events from
 * registered gateways to registered actors. It supports two operating modes:
 *
 * <ul>
 *   <li><b>{@link OperatingMode#COMPRESSED_TIME}</b> — historical replay.
 *       Events are routed through the {@link TimeMachine}, which enforces
 *       causal ordering and blocks dispatch until every clock-driving gateway
 *       has committed its next event to the queue.</li>
 *   <li><b>{@link OperatingMode#REAL_TIME}</b> — live operation. Events are
 *       placed on a {@link LinkedBlockingQueue} and dispatched in arrival order
 *       on the engine thread.</li>
 * </ul>
 *
 * <h2>Lifecycle</h2>
 * <pre>
 *   run()
 *     initialize()   → INITIALIZED
 *     connect()      → PRESTART  (registers publisher gateways with TimeMachine)
 *     [STARTED]
 *     processEventQueue()  ← blocks until shutdown
 *     shutDownActors()
 *     disconnect()
 *     [COMPLETE]
 * </pre>
 *
 * <h2>Startup and shutdown signals</h2>
 * <p>The engine injects {@link EngineCommand} events onto the bus to
 * trigger actor lifecycle calls at the right simulation time:
 * <ul>
 *   <li>In {@code COMPRESSED_TIME}, {@code startUpActors} is called directly
 *       at {@code startTime} before the dispatch loop begins.</li>
 *   <li>In {@code REAL_TIME}, a {@code STARTUP} command is scheduled via a
 *       single-thread executor to fire at {@code startTime}.</li>
 *   <li>A {@code SHUTDOWN} command is enqueued when end time is reached or
 *       all publisher gateways have disconnected.</li>
 * </ul>
 *
 * <h2>Extending</h2>
 * <p>Subclasses implement actor management:
 * {@link #timeEventToActors}, {@link #startUpActors}, {@link #shutDownActors},
 * and {@link #actorsReadyToShutDown}.
 */
public abstract class AbstractEngine extends AbstractGateway {

    // ------------------------------------------------------------------
    // Internal topic for EngineCommands
    // ------------------------------------------------------------------

    static final Topic<EngineCommand> ENGINE_COMMAND_TOPIC =
            new Topic<>("pulse.engine.command", EngineCommand.class);

    // ------------------------------------------------------------------
    // Dispatch infrastructure
    // ------------------------------------------------------------------

    private final TimeMachine timeMachine = new TimeMachine();

    /** Used in REAL_TIME mode instead of the TimeMachine. */
    private final LinkedBlockingQueue<TimeEvent> liveQueue = new LinkedBlockingQueue<>();

    private volatile long     lastEventTime = -1L;
    private final AtomicBoolean startUpDone = new AtomicBoolean(false);

    // ------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------

    protected AbstractEngine(String name, long startTime, long endTime) {
        super(name, startTime, endTime);
    }

    // ------------------------------------------------------------------
    // Gateway — receive events from registered publisher gateways
    // ------------------------------------------------------------------

    /**
     * Receives an event published by a registered gateway and routes it
     * through the appropriate dispatch path.
     *
     * <p>{@link EngineCommand} events are intercepted before reaching the
     * dispatch queues and handled directly. All other events are forwarded
     * to {@link #processIncomingEvent}.
     */
    @Override
    public <E extends Event> void onEvent(Topic<E> topic, E event) {
        if (event instanceof EngineCommand cmd) {
            handleEngineCommand(cmd);
            return;
        }
        switch (status()) {
            case BLANK, INITIALIZED -> { /* drop — engine not running yet */ }
            case PRESTART, STARTED, WRAP_UP -> processIncomingEvent(topic, event);
            case PAUSED -> { /* TODO: buffer events during pause */ }
            case FINALIZE, STOPPED, COMPLETE -> { /* drop — past end of window */ }
        }
    }

    /**
     * Routes an event from a gateway through the appropriate dispatch path.
     * Recovers the originating gateway from the {@code publishers} routing table.
     */
    private <E extends Event> void processIncomingEvent(Topic<E> topic, E event) {
        Gateway origin = publisherForKey(topic, event.key());

        if (event.eventTime() < startTime()) return; // before window — drop

        switch (operatingMode()) {
            case COMPRESSED_TIME -> addToTimeMachine(topic, event, origin);
            case REAL_TIME        -> addToLiveQueue(topic, event, origin);
            case MIXED            -> throw new UnsupportedOperationException(
                    "MIXED operating mode is not yet implemented");
            case UNDEFINED        -> throw new IllegalStateException(
                    "Engine received an event before operating mode was determined");
        }
    }

    private <E extends Event> void addToTimeMachine(Topic<E> topic, E event, Gateway origin) {
        if (event.eventTime() > endTime()) {
            synchronized (this) {
                if (status().ordinal() < GatewayStatus.FINALIZE.ordinal()) {
                    setStatus(GatewayStatus.FINALIZE);
                }
            }
            return;
        }
        if (status() == GatewayStatus.FINALIZE) return;

        try {
            timeMachine.addEvent(event, topic, origin != null ? origin : this);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private <E extends Event> void addToLiveQueue(Topic<E> topic, E event, Gateway origin) {
        if (event.eventTime() > endTime()) {
            enqueueShutdown(endTime());
            return;
        }
        long now = System.currentTimeMillis();
        liveQueue.offer(new TimeEvent(event, topic, origin != null ? origin : this, now, now));
    }

    // ------------------------------------------------------------------
    // Gateway — forward actor-published events to subscriber gateways
    // ------------------------------------------------------------------

    /**
     * Publishes an event produced by an actor to the registered subscriber
     * gateway for that {@code (topic, key)} pair, if one exists.
     *
     * <p>Also routes the event back through the engine's own dispatch path
     * if any actor has subscribed to it (self-queue).
     */
    @Override
    @SuppressWarnings("unchecked")
    public <E extends Event> void publish(Topic<E> topic, E event) {
        Gateway sub = subscriberForKey(topic, event.key());
        if (sub != null && sub != this) {
            sub.onEvent(topic, event);
        }
        // Re-enter own dispatch for actor-to-actor routing
        onEvent(topic, event);
    }

    // ------------------------------------------------------------------
    // Gateway — registration overrides
    // ------------------------------------------------------------------

    /** Registers a publisher and tracks it in the active gateway set. */
    @Override
    public synchronized void registerPublisher(Gateway publisher, Topic<?> topic, List<String> keys) {
        super.registerPublisher(publisher, topic, keys);
    }

    /**
     * Unregisters a publisher; if it was a clock-driving gateway, removes it
     * from the {@link TimeMachine}. When no publishers remain, enqueues a
     * {@link EngineCommand.Kind#SHUTDOWN} signal.
     */
    @Override
    public synchronized void unregisterPublisher(Gateway publisher, Topic<?> topic, List<String> keys) {
        super.unregisterPublisher(publisher, topic, keys);

        if (operatingMode() == OperatingMode.COMPRESSED_TIME && publisher.drivesClock()) {
            timeMachine.removeGateway(publisher);
        }

        GatewayStatus s = status();
        if (s == GatewayStatus.STARTED || s == GatewayStatus.WRAP_UP) {
            if (registeredPublishers().isEmpty()) {
                enqueueShutdown(lastEventTime > 0 ? lastEventTime : endTime());
            }
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /**
     * Initialises the engine and determines the {@link OperatingMode}.
     * Subclasses may override but must call {@code super.initialize()}.
     */
    @Override
    protected void initialize() {
        super.initialize(); // sets INITIALIZED and determines operatingMode
    }

    /**
     * Connects the engine; in {@code COMPRESSED_TIME} mode, registers all
     * publisher gateways with the {@link TimeMachine}.
     * Subclasses may override but must call {@code super.connect()}.
     */
    @Override
    protected void connect() {
        super.connect(); // sets PRESTART
        if (operatingMode() == OperatingMode.COMPRESSED_TIME) {
            for (Gateway g : registeredPublishers()) {
                timeMachine.registerGateway(g);
            }
        }
    }

    /**
     * Disconnects all connected publisher and subscriber gateways, then
     * calls {@code super.disconnect()}.
     */
    @Override
    public void disconnect() {
        // Snapshot before iterating — disconnect() may modify the sets
        Set<Gateway> subs = new HashSet<>(registeredSubscribers());
        Set<Gateway> pubs = new HashSet<>(registeredPublishers());

        for (Gateway g : subs) {
            if (g != this && g.connected()) g.disconnect();
        }
        for (Gateway g : pubs) {
            if (g != this && g.connected()) g.disconnect();
        }
        super.disconnect();
    }

    /**
     * Full engine lifecycle. Blocks until shutdown is complete.
     *
     * <ol>
     *   <li>Initialise and connect.</li>
     *   <li>Start actors (compressed time: immediately; real time: at startTime).</li>
     *   <li>Run the dispatch loop until shutdown.</li>
     *   <li>Shut down actors and disconnect.</li>
     * </ol>
     */
    @Override
    public void run() {
        try {
            initialize();
            connect();
            setStatus(GatewayStatus.STARTED);

            switch (operatingMode()) {
                case COMPRESSED_TIME -> {
                    startUpActors(startTime());
                    processEventQueueSim();
                }
                case REAL_TIME -> {
                    scheduleStartup();
                    processEventQueueLive();
                }
                default -> throw new UnsupportedOperationException(
                        "Operating mode " + operatingMode() + " not supported by engine");
            }

            shutDownActors(lastEventTime > 0 ? lastEventTime : endTime());
            disconnect();
            setStatus(GatewayStatus.COMPLETE);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            setStatus(GatewayStatus.STOPPED);
        } catch (Exception e) {
            setStatus(GatewayStatus.STOPPED);
            throw new RuntimeException("Engine terminated with error", e);
        }
    }

    // ------------------------------------------------------------------
    // Dispatch loops
    // ------------------------------------------------------------------

    private void processEventQueueSim() throws InterruptedException {
        while (true) {
            TimeEvent te = timeMachine.take();
            if (te == null) break; // cleared during shutdown

            GatewayStatus s = status();
            if (s == GatewayStatus.WRAP_UP || s == GatewayStatus.FINALIZE) {
                timeMachine.done(te);
                if (timeMachine.size() < 1 || te.event().eventTime() > endTime()) {
                    setStatus(GatewayStatus.STOPPED);
                    break;
                }
                continue;
            }
            if (s == GatewayStatus.STOPPED) {
                timeMachine.done(te);
                break;
            }

            lastEventTime = te.event().eventTime();

            if (te.event().eventTime() <= endTime()) {
                dispatchTimeEvent(te);
            }
            timeMachine.done(te);
        }
    }

    private void processEventQueueLive() throws InterruptedException {
        while (true) {
            TimeEvent te = liveQueue.take();

            if (te.event() instanceof EngineCommand cmd) {
                if (cmd.kind() == EngineCommand.Kind.STARTUP) {
                    if (!startUpDone.get()) {
                        startUpActors(cmd.eventTime());
                        startUpDone.set(true);
                    }
                    continue;
                }
                if (cmd.kind() == EngineCommand.Kind.SHUTDOWN) {
                    setStatus(GatewayStatus.WRAP_UP);
                    setStatus(GatewayStatus.STOPPED);
                    break;
                }
            }

            if (status() == GatewayStatus.STOPPED) break;

            lastEventTime = te.event().eventTime();
            if (te.event().eventTime() <= endTime()) {
                dispatchTimeEvent(te);
            }
        }
    }

    // ------------------------------------------------------------------
    // Dispatch helpers
    // ------------------------------------------------------------------

    /**
     * Dispatches a single {@link TimeEvent}: forwards to any registered
     * subscriber gateway for that {@code (topic, key)}, then delivers to
     * actors via {@link #timeEventToActors}.
     */
    @SuppressWarnings("unchecked")
    protected final void dispatchTimeEvent(TimeEvent te) {
        // Forward to a subscriber gateway if one is registered
        Gateway sub = subscriberForKey(te.topic(), te.event().key());
        if (sub != null && sub != this) {
            dispatchTyped(te, sub);
        }
        if (startUpDone.get()) {
            timeEventToActors(te);
        }
    }

    /** Typed dispatch: recovers {@code E} via {@code Topic#eventType()} to avoid raw casts at call sites. */
    @SuppressWarnings("unchecked")
    protected final <E extends Event> void dispatchTyped(TimeEvent te, Sub target) {
        Topic<E> topic = (Topic<E>) te.topic();
        E event = topic.eventType().cast(te.event());
        target.onEvent(topic, event);
    }

    // ------------------------------------------------------------------
    // EngineCommand helpers
    // ------------------------------------------------------------------

    private void handleEngineCommand(EngineCommand cmd) {
        switch (cmd.kind()) {
            case STARTUP -> {
                if (!startUpDone.getAndSet(true)) {
                    startUpActors(cmd.eventTime());
                }
            }
            case SHUTDOWN -> enqueueShutdown(cmd.eventTime());
        }
    }

    private void enqueueShutdown(long atTime) {
        long shutdownTime = Math.max(atTime, endTime() + 1L);
        EngineCommand cmd = new EngineCommand(EngineCommand.Kind.SHUTDOWN, shutdownTime);
        long now = System.currentTimeMillis();
        TimeEvent te = new TimeEvent(cmd, ENGINE_COMMAND_TOPIC, this, now, now);
        if (operatingMode() == OperatingMode.COMPRESSED_TIME) {
            try {
                timeMachine.addEvent(cmd, ENGINE_COMMAND_TOPIC, this);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } else {
            liveQueue.offer(te);
        }
    }

    private void scheduleStartup() {
        long delay = Math.max(0L, startTime() - System.currentTimeMillis());
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, name() + "-startup-scheduler");
            t.setDaemon(true);
            return t;
        });
        long startTime = startTime();
        scheduler.schedule(() -> {
            EngineCommand cmd = new EngineCommand(EngineCommand.Kind.STARTUP, startTime);
            long now = System.currentTimeMillis();
            liveQueue.offer(new TimeEvent(cmd, ENGINE_COMMAND_TOPIC, this, now, now));
            scheduler.shutdown();
        }, delay, TimeUnit.MILLISECONDS);
    }

    // ------------------------------------------------------------------
    // Abstract actor lifecycle — implemented by concrete engines
    // ------------------------------------------------------------------

    /**
     * Delivers a time-ordered event to all actors subscribed to its topic and key.
     * Called on the engine's dispatch thread; implementations must be fast.
     *
     * @param te the event to dispatch
     */
    protected abstract void timeEventToActors(TimeEvent te);

    /**
     * Called once when the engine reaches its start time.
     * Implementations should perform actor initialisation and start any
     * actors that run on their own threads.
     *
     * @param time epoch millis of the start time
     */
    protected abstract void startUpActors(long time);

    /**
     * Called once when the engine is shutting down.
     * Implementations should stop actor threads and perform teardown.
     *
     * @param time epoch millis of the last processed event
     */
    protected abstract void shutDownActors(long time);

    /**
     * @return {@code true} if all runnable actors have finished and the
     *         engine may proceed to stop
     */
    protected abstract boolean actorsReadyToShutDown();
}
