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

    private final TimeMachine timeMachine;

    /** Used in REAL_TIME mode instead of the TimeMachine. */
    private final LinkedBlockingQueue<TimeEvent> liveQueue = new LinkedBlockingQueue<>();

    private volatile long     lastEventTime = -1L;
    private final AtomicBoolean startUpDone = new AtomicBoolean(false);

    // ------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------

    protected AbstractEngine(String name, long startTime, long endTime) {
        super(name, startTime, endTime);
        this.timeMachine = new TimeMachine(name + ".timemachine");
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
    public <P extends Datum> void onEvent(Topic<P> topic, P payload) {
        if (payload instanceof EngineCommand cmd) {
            handleEngineCommand(cmd);
            return;
        }
        switch (status()) {
            case BLANK, INITIALIZED -> { /* drop — engine not running yet */ }
            case PRESTART, STARTED, WRAP_UP -> processIncomingEvent(topic, payload);
            case PAUSED -> { /* TODO: buffer events during pause */ }
            case FINALIZE, STOPPED, COMPLETE -> { /* drop — past end of window */ }
        }
    }

    /**
     * Routes a payload from a gateway through the appropriate dispatch path.
     * Recovers the originating gateway from the {@code publishers} routing table.
     */
    private <P extends Datum> void processIncomingEvent(Topic<P> topic, P payload) {
        Gateway origin = publisherForKey(topic, payload.getDatumKey());

        if (payload.getDatumTime() < startTime()) return; // before window — drop

        switch (operatingMode()) {
            case COMPRESSED_TIME -> addToTimeMachine(topic, payload, origin);
            case REAL_TIME        -> addToLiveQueue(topic, payload, origin);
            case MIXED            -> throw new UnsupportedOperationException(
                    "MIXED operating mode is not yet implemented");
            case UNDEFINED        -> throw new IllegalStateException(
                    "Engine received an event before operating mode was determined");
        }
    }

    private <P extends Datum> void addToTimeMachine(Topic<P> topic, P payload, Gateway origin) {
        if (payload.getDatumTime() > endTime()) {
            synchronized (this) {
                if (status().ordinal() < GatewayStatus.FINALIZE.ordinal()) {
                    setStatus(GatewayStatus.FINALIZE);
                }
            }
            return;
        }
        if (status() == GatewayStatus.FINALIZE) return;

        try {
            timeMachine.addEvent(payload, topic, origin != null ? origin : this);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private <P extends Datum> void addToLiveQueue(Topic<P> topic, P payload, Gateway origin) {
        if (payload.getDatumTime() > endTime()) {
            enqueueShutdown(endTime());
            return;
        }
        long now = System.currentTimeMillis();
        liveQueue.offer(new TimeEvent(payload, topic, origin != null ? origin : this, now, now));
    }

    // ------------------------------------------------------------------
    // Gateway — forward actor-published events to subscriber gateways
    // ------------------------------------------------------------------

    /**
     * Entry point for events an actor publishes back onto the bus.
     *
     * <p>The payload is funnelled into the same time-ordered path as
     * gateway-produced events (via {@link #onEvent}); the dispatch loop is the
     * single place that delivers each event to its subscriber gateways and
     * actors. Doing delivery only there keeps ordering deterministic and avoids
     * double-delivery.
     */
    @Override
    public <P extends Datum> void publish(Topic<P> topic, P payload) {
        onEvent(topic, payload);
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
                    startUpDone.set(true);   // actors are up; allow dispatch
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
            if (te == null) break; // queue drained and no producers remain

            // The shutdown signal is enqueued (at endTime+1) when the last
            // clock-driving publisher unregisters, so it sorts after all real
            // events and terminates the loop once they have been dispatched.
            if (te.payload() instanceof EngineCommand cmd) {
                timeMachine.done(te);
                if (cmd.kind() == EngineCommand.Kind.SHUTDOWN) {
                    setStatus(GatewayStatus.WRAP_UP);
                    setStatus(GatewayStatus.STOPPED);
                    break;
                }
                continue; // STARTUP is handled directly in compressed time
            }

            lastEventTime = te.eventTime();
            if (te.eventTime() <= endTime()) {
                dispatchTimeEvent(te);
            }
            timeMachine.done(te);
        }
    }

    private void processEventQueueLive() throws InterruptedException {
        while (true) {
            TimeEvent te = liveQueue.take();

            if (te.payload() instanceof EngineCommand cmd) {
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

            lastEventTime = te.eventTime();
            if (te.eventTime() <= endTime()) {
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
    protected final void dispatchTimeEvent(TimeEvent te) {
        log.largeInfo(() -> "dispatch " + te.topic().name() + " key=" + te.key()
                + " @ " + te.eventTime());
        // Forward to a subscriber gateway if one is registered
        Gateway sub = subscriberForKey(te.topic(), te.key());
        if (sub != null && sub != this) {
            dispatchTyped(te, sub);
        }
        if (startUpDone.get()) {
            timeEventToActors(te);
        }
    }

    /** Typed dispatch: recovers {@code P} via {@link Topic#payloadType()} to avoid raw casts at call sites. */
    @SuppressWarnings("unchecked")
    protected final <P extends Datum> void dispatchTyped(TimeEvent te, Sub target) {
        Topic<P> topic = (Topic<P>) te.topic();
        P payload = topic.payloadType().cast(te.payload());
        target.onEvent(topic, payload);
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
