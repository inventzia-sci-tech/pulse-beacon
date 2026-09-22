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
import com.inventzia.pulse.data.datum.DatumTypeRegistry;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
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
 *   <li>In <b>both</b> modes, consumers are started before the dispatch loop
 *       begins (carrying the logical {@code startTime}) and {@code startUpDone}
 *       is set, so no event is ever delivered before startup. Starting eagerly
 *       in {@code REAL_TIME} too avoids a race where an event arriving at
 *       {@code startTime} could be dispatched before a scheduled startup signal.</li>
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

    /**
     * The datum-type universe for this run, established once by {@link #initialize()} via the
     * explicit SPI discovery ({@link DatumTypeRegistry#discoverProviders()}). {@link #runInfo()}
     * only reads it; discovery is never triggered as a side effect of an observability call.
     */
    private volatile DatumTypeRegistry typeRegistry;

    /**
     * Run-lifecycle observers (see {@link RunListener}). An orchestrator attaches one to be told,
     * on the engine thread, when the run is initialised (before dispatch) and when it terminates.
     * Copy-on-write so notification iterates without holding a lock and attachment is thread-safe.
     * The engine holds no other reference to what a listener does: no filesystem, no manifest, no
     * appender ever crosses this boundary — only the two lifecycle facts.
     */
    private final List<RunListener> runListeners = new CopyOnWriteArrayList<>();

    /**
     * Used in REAL_TIME mode instead of the TimeMachine.
     *
     * <p><b>Beta limitation — no backpressure.</b> This queue is unbounded: a live
     * producer that outpaces the dispatch thread grows it without limit. Acceptable for
     * the current demo-grade real-time support (COMPRESSED_TIME, the deterministic replay
     * path, is permit-paced and bounded by the number of gateways). A future real-time
     * hardening pass should choose a policy — bounded + block, or drop-oldest with a
     * dropped-event counter.
     */
    private final LinkedBlockingQueue<TimeEvent> liveQueue = new LinkedBlockingQueue<>();

    private volatile long     lastEventTime = -1L;
    private final AtomicBoolean startUpDone = new AtomicBoolean(false);

    /**
     * Last event time accepted from each source gateway, for the per-gateway
     * monotonicity guard (compressed time). See {@link #acceptMonotonic} for the
     * single-producer-per-gateway contract that lets the {@link ConcurrentHashMap}
     * carry the read-then-write without a per-gateway lock.
     */
    private final Map<Gateway, Long> lastAcceptedTime = new ConcurrentHashMap<>();

    // Deterministic equal-event-time tie-break inputs (see TimeEventComparator):
    /** Stable per-origin index, assigned in registration order (never from iteration). */
    private final Map<Gateway, Integer> originIndex = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger nextOriginIndex =
            new java.util.concurrent.atomic.AtomicInteger();
    /** Origins (source gateways) registered high priority — their equal-time events sort first. */
    private final Set<Gateway> highPriorityOrigins = ConcurrentHashMap.newKeySet();

    // ------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------

    protected AbstractEngine(String name, long startTime, long endTime) {
        super(name, startTime, endTime);
        this.timeMachine = new TimeMachine(name + ".timemachine");
    }

    // ------------------------------------------------------------------
    // Observer API — read-only run description (for orchestrators, not actors)
    // ------------------------------------------------------------------

    /**
     * The run's {@link RunInfo}: its {@link OperatingMode} and time window. This is
     * the sanctioned, public way for the code orchestrating a run to record or report
     * what it did (run manifests, telemetry, audit records, mode-selection tests)
     * without reaching into the protected {@link #operatingMode()} accessor.
     *
     * <p>Before {@link #initialize()} the mode is {@link OperatingMode#UNDEFINED};
     * after it, {@link OperatingMode#REAL_TIME} or {@link OperatingMode#COMPRESSED_TIME}.
     *
     * <p>Only the run orchestrator (which holds the engine) can call this. Actors hold
     * only a {@link Pub}, so a strategy cannot obtain the mode and branch on it — see
     * {@link AbstractGateway#operatingMode()} for why that boundary matters.
     *
     * @return an immutable snapshot of the run's mode and window
     */
    public RunInfo runInfo() {
        // Read the universe established at initialize(); fall back to the accessor only if runInfo()
        // is called before the engine initialised (no run yet), so this never bootstraps discovery
        // as its own side effect in the normal flow.
        DatumTypeRegistry registry = typeRegistry;
        if (registry == null) {
            registry = DatumTypeRegistry.defaultRegistry();
        }
        List<String> providerIds = registry.providers().stream()
                .map(DatumTypeRegistry.ProviderInfo::providerId)
                .sorted()
                .toList();
        return new RunInfo(operatingMode(), startTime(), endTime(),
                registry.fingerprint(), providerIds);
    }

    /**
     * Attaches a {@link RunListener} to be notified, on the engine thread, when this run is
     * initialised (before dispatch) and when it terminates. Attach before {@link #run()}; a listener
     * attached after initialisation misses {@link RunListener#onRunInitialized}. Intended for run
     * orchestration (recording, manifests, telemetry); the engine itself remains oblivious to what
     * the listener does.
     *
     * @param listener the observer to attach (ignored if {@code null})
     */
    public void addRunListener(RunListener listener) {
        if (listener != null) {
            runListeners.add(listener);
        }
    }

    /** Fires {@link RunListener#onRunInitialized} on every listener, isolating and logging faults. */
    private void notifyRunInitialized() {
        if (runListeners.isEmpty()) return;
        RunInfo info = runInfo();
        for (RunListener l : runListeners) {
            try {
                l.onRunInitialized(info);
            } catch (Exception ex) {
                log.severe("run listener threw in onRunInitialized; isolating and continuing", ex);
            }
        }
    }

    /** Fires {@link RunListener#onRunTerminated} on every listener, isolating and logging faults. */
    private void notifyRunTerminated(RunOutcome outcome) {
        for (RunListener l : runListeners) {
            try {
                l.onRunTerminated(outcome);
            } catch (Exception ex) {
                log.severe("run listener threw in onRunTerminated; isolating and continuing", ex);
            }
        }
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

        // Per-gateway monotonicity: a source may not go backwards in time. A later
        // submission with an earlier timestamp than this gateway already emitted would
        // reorder already-dispatched history and break the deterministic merge, so it is
        // rejected (dropped). See acceptMonotonic.
        if (origin != null && !acceptMonotonic(origin, payload.getDatumTime(), topic)) {
            return;
        }

        try {
            Gateway o = origin != null ? origin : this;
            timeMachine.addEvent(payload, topic, o, rankOf(o), indexOf(o));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Enforces per-gateway timestamp monotonicity: the source's own stream must be
     * non-decreasing. Same-tick ({@code ==}) and later events advance the gateway's
     * frontier and are accepted; a strictly-earlier event is a violation — dropped and
     * logged, without advancing the frontier or failing the gateway (a hard failure on
     * the gateway's thread would leave the TimeMachine barrier waiting for a dead
     * clock-driver). Compressed-time only; REAL_TIME is arrival-ordered by contract.
     *
     * <p><b>Threading contract.</b> The read-then-write of {@code lastAcceptedTime} is
     * <em>not</em> atomic, and relies on a single producer thread per gateway: a gateway
     * feeds the engine only from its own source loop (see {@code Gateway.run}), so no two
     * threads ever call this with the same {@code origin} concurrently, and each origin's
     * frontier is touched by exactly one thread. For clock-driving gateways the
     * TimeMachine write permit reinforces this (at most one event in flight per gateway).
     * A gateway that fanned events in from several threads would violate this contract and
     * must serialize its own emission before handing off to the engine.
     *
     * @return {@code true} to accept the event, {@code false} to drop it
     */
    private boolean acceptMonotonic(Gateway origin, long time, Topic<?> topic) {
        Long last = lastAcceptedTime.get(origin);
        if (last != null && time < last) {
            log.severe("monotonicity violation: gateway '" + origin.name() + "' submitted "
                    + topic.name() + " @ " + time + " after " + last
                    + "; dropping (an earlier timestamp would retroactively reorder dispatched history)");
            return false;
        }
        lastAcceptedTime.put(origin, time);
        return true;
    }

    private <P extends Datum> void addToLiveQueue(Topic<P> topic, P payload, Gateway origin) {
        if (payload.getDatumTime() > endTime()) {
            enqueueShutdown(endTime());
            return;
        }
        long now = System.currentTimeMillis();
        liveQueue.offer(TimeEvent.unordered(payload, topic, origin != null ? origin : this, now, now));
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
     *
     * <h2>Causality</h2>
     * <p>An actor may only publish events at or after the simulation time it is
     * currently handling. A <em>same-tick</em> derived event (equal time) and a
     * future event are allowed; a publish with an event time <em>before</em> the
     * event being dispatched is acausal — it would let a retroactive event jump
     * ahead of work the engine has already done, breaking deterministic
     * event-time ordering — and is rejected with a {@link CausalityException}.
     * (Gateway sources reach the bus through {@link #onEvent} directly, not here,
     * so this applies only to actor publishes.)
     */
    @Override
    public <P extends Datum> void publish(Topic<P> topic, P payload) {
        long current = lastEventTime;
        if (current >= 0 && payload.getDatumTime() < current) {
            throw new CausalityException(
                    "acausal publish on '" + topic.name() + "' key=" + payload.getDatumKey()
                    + ": event time " + payload.getDatumTime()
                    + " precedes the current simulation time " + current
                    + " (publish at or after the current tick; same-tick derived events are allowed)");
        }
        onEvent(topic, payload);
    }

    // ------------------------------------------------------------------
    // Gateway — registration overrides
    // ------------------------------------------------------------------

    /** Registers a publisher (at normal priority) and tracks it in the active gateway set. */
    @Override
    public synchronized void registerPublisher(Gateway publisher, Topic<?> topic, List<String> keys) {
        // Assign a stable index in registration-call order (the first time this gateway is
        // registered), for the deterministic equal-event-time tie-break.
        originIndex.computeIfAbsent(publisher, g -> nextOriginIndex.getAndIncrement());
        super.registerPublisher(publisher, topic, keys);
    }

    /**
     * Registers a publisher, optionally as <b>high priority</b>: among events with the
     * same event time, a high-priority source's events are dispatched before a
     * normal-priority source's (see {@link TimeEventComparator}). Priority affects only
     * this equal-time tie-break, not which events are produced.
     */
    public synchronized void registerPublisher(Gateway publisher, Topic<?> topic, List<String> keys,
                                               boolean highPriority) {
        if (highPriority) {
            highPriorityOrigins.add(publisher);
        }
        registerPublisher(publisher, topic, keys);
    }

    /** The origin's tie-break rank: 0 (high priority) sorts before 1 (normal). */
    private int rankOf(Gateway origin) {
        return highPriorityOrigins.contains(origin) ? 0 : 1;
    }

    /** The origin's stable tie-break index (registration order; assigned lazily for the engine itself). */
    private int indexOf(Gateway origin) {
        return originIndex.computeIfAbsent(origin, g -> nextOriginIndex.getAndIncrement());
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
     * Initialises the engine, determines the {@link OperatingMode}, and discovers the datum-type
     * Service Providers. Subclasses may override but must call {@code super.initialize()}.
     */
    @Override
    protected void initialize() {
        super.initialize(); // sets INITIALIZED and determines operatingMode

        // Discover the datum-type Service Providers (the SPI): we must establish the type universe
        // before the run begins. This runs the Service Provider Interface discovery — the core
        // provider seeded directly, plus every extension DatumTypeProvider advertised on the
        // classpath via java.util.ServiceLoader (META-INF/services) — validated and frozen.
        //
        // Do it here, explicitly and exactly once, at a deterministic lifecycle point, rather than
        // let it be bootstrapped lazily as a side effect of the first runInfo() read. Two upshots:
        // any provider/type conflict surfaces here at startup (not later on an observability call),
        // and runInfo() becomes a pure read of the universe established below.
        this.typeRegistry = DatumTypeRegistry.discoverProviders();
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
     *   <li>Start consumers before the dispatch loop (the deterministic start barrier).</li>
     *   <li>Run the dispatch loop until shutdown.</li>
     *   <li>Shut down actors and disconnect.</li>
     * </ol>
     */
    @Override
    public void run() {
        // Terminal outcome is reported to run listeners exactly once, from the finally block, so it
        // fires in every exit path (clean completion, failure, interruption) without duplication. The
        // failure path records the error here and still rethrows below; the finally runs first.
        Throwable terminalError = null;
        boolean terminalInterrupt = false;
        try {
            initialize();
            // Mode and the datum-type universe are now fixed; announce the run to listeners before any
            // event is dispatched, so a recorder opens with the engine's authoritative RunInfo.
            notifyRunInitialized();
            connect();
            setStatus(GatewayStatus.STARTED);

            switch (operatingMode()) {
                case COMPRESSED_TIME -> {
                    startUpActors(startTime());
                    startUpGateways(startTime());   // engine-driven sink lifecycle (barrier)
                    startUpDone.set(true);          // consumers are up; allow dispatch
                    processEventQueueSim();
                }
                case REAL_TIME -> {
                    // Start consumers deterministically before the dispatch loop,
                    // exactly as in compressed time. Previously a STARTUP command
                    // was scheduled onto the live queue to fire at startTime, but
                    // that raced real producers: an event arriving at the same
                    // instant could be dequeued first and dropped (startUpDone
                    // still false). onStartUp carries the logical start time, so
                    // running it eagerly here (and gating dispatch on startUpDone)
                    // keeps the start barrier without the race.
                    startUpActors(startTime());
                    startUpGateways(startTime());
                    startUpDone.set(true);
                    processEventQueueLive();
                }
                default -> throw new UnsupportedOperationException(
                        "Operating mode " + operatingMode() + " not supported by engine");
            }

            long shutdownTime = lastEventTime > 0 ? lastEventTime : endTime();
            shutDownActors(shutdownTime);
            shutDownGateways(shutdownTime);   // engine-driven sink lifecycle (barrier)
            disconnect();
            setStatus(GatewayStatus.COMPLETE);

        } catch (InterruptedException e) {
            terminalInterrupt = true;
            Thread.currentThread().interrupt();
            abortCleanup();
            setStatus(GatewayStatus.STOPPED);
        } catch (Exception e) {
            terminalError = e;
            abortCleanup();
            setStatus(GatewayStatus.STOPPED);
            throw new RuntimeException("Engine terminated with error", e);
        } finally {
            // Publish the engine's own terminal outcome (never the recorder's). Runs before the
            // failure path's rethrow propagates, so listeners always see the outcome exactly once.
            notifyRunTerminated(new RunOutcome(status(), terminalError, terminalInterrupt));
        }
    }

    /**
     * Best-effort teardown after an abnormal exit from the dispatch loop. Unlike
     * the normal path (which drains naturally and disconnects), here the gateway
     * threads are still running, so we must both unblock and disconnect them:
     *
     * <ul>
     *   <li>{@link TimeMachine#shutdown()} releases every clock-driver's write
     *       permit, so a producer blocked in {@code addEvent} (waiting on a
     *       permit the now-dead consumer will never release) unblocks and bails;</li>
     *   <li>{@link #abortConsumers()} ends every cross-language consumer's streamer
     *       loop (the normal {@code onShutDown} path does not run on abort), so a
     *       sink/actor blocked in {@code takeNext()} does not hang forever;</li>
     *   <li>{@link #disconnect()} tears down subscriber and publisher gateways.</li>
     * </ul>
     *
     * Each step is guarded so a failure in one still lets the others run, and
     * each must be non-blocking (consumer acks may never come on the failure path).
     */
    private void abortCleanup() {
        try {
            timeMachine.shutdown();
        } catch (Exception ex) {
            log.severe("time machine shutdown failed during cleanup", ex);
        }
        try {
            abortConsumers();
        } catch (Exception ex) {
            log.severe("consumer abort failed during cleanup", ex);
        }
        try {
            disconnect();
        } catch (Exception ex) {
            log.severe("gateway disconnect failed during cleanup", ex);
        }
    }

    /**
     * Best-effort, non-blocking termination of every consumer's foreign-side
     * streamer on the abort path: subscriber (sink) gateways here, plus actors
     * via {@link #abortActors()} (the actor list lives in the concrete engine).
     */
    private void abortConsumers() {
        for (Gateway g : registeredSubscribers()) {
            if (g != this && g instanceof AbstractGateway base) {
                base.onAbort();
            }
        }
        abortActors();
    }

    /**
     * Hook for the concrete engine to call {@link AbstractActor#onAbort()} on each
     * registered actor during abnormal cleanup. Default: no-op.
     */
    protected void abortActors() {
        // no-op by default
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
            // done(te) must run even if dispatch throws (a subscriber-gateway
            // failure is fatal); otherwise the originating clock-driver's write
            // permit is never released and it blocks forever in addEvent.
            try {
                if (te.eventTime() <= endTime()) {
                    dispatchTimeEvent(te);
                }
            } finally {
                timeMachine.done(te);
            }
        }
    }

    private void processEventQueueLive() throws InterruptedException {
        while (true) {
            TimeEvent te = liveQueue.take();

            if (te.payload() instanceof EngineCommand cmd) {
                if (cmd.kind() == EngineCommand.Kind.SHUTDOWN) {
                    setStatus(GatewayStatus.WRAP_UP);
                    setStatus(GatewayStatus.STOPPED);
                    break;
                }
                continue; // never dispatch an engine command as data (startup is eager now)
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
        // Deliver only once startup is complete, so consumers (sink gateways and
        // actors) never see data before their onStartUp — the start barrier.
        if (!startUpDone.get()) {
            return;
        }
        // Forward to a subscriber gateway if one is registered
        Gateway sub = subscriberForKey(te.topic(), te.key());
        if (sub != null && sub != this) {
            dispatchTyped(te, sub);
        }
        timeEventToActors(te);
    }

    /**
     * Drives subscriber-gateway startup, mirroring {@link #startUpActors}: every
     * registered subscriber (sink) gateway gets {@link AbstractGateway#onStartUp}
     * on the engine's dispatch thread before any data is dispatched. For a
     * cross-language sink this blocks on the foreign ack, so the call is a barrier
     * — all sinks are started before the run proceeds.
     */
    private void startUpGateways(long time) {
        for (Gateway g : registeredSubscribers()) {
            if (g != this && g instanceof AbstractGateway base) {
                base.onStartUp(time);
            }
        }
    }

    /**
     * Drives subscriber-gateway shutdown, mirroring {@link #shutDownActors}: every
     * registered subscriber (sink) gateway gets {@link AbstractGateway#onShutDown}
     * on the dispatch thread after the last data event, barriering on each ack so
     * the run only completes once every sink has confirmed it stopped.
     */
    private void shutDownGateways(long time) {
        for (Gateway g : registeredSubscribers()) {
            if (g != this && g instanceof AbstractGateway base) {
                base.onShutDown(time);
            }
        }
    }

    /** Typed dispatch: recovers {@code P} via {@link Topic#payloadType()} to avoid raw casts at call sites. */
    @SuppressWarnings("unchecked")
    protected final <P extends Datum> void dispatchTyped(TimeEvent te, Sub target) {
        Topic<P> topic = (Topic<P>) te.topic();
        P payload = topic.payloadType().cast(te.payload());
        target.onEvent(topic, payload);
    }

    /**
     * Dispatches an event to a single actor, isolating its failure: a throwing
     * actor is logged (with its stack trace) and skipped, never aborting the run
     * or starving the other actors subscribed to the same event. Business-logic
     * faults are contained here; a broken boundary (subscriber gateway) is not —
     * that is dispatched directly via {@link #dispatchTyped} and is fatal.
     */
    protected final void dispatchToActor(TimeEvent te, Actor actor) {
        try {
            dispatchTyped(te, actor);
        } catch (Exception ex) {
            log.severe("actor threw handling " + te.topic().name() + " key=" + te.key()
                    + " @ " + te.eventTime() + "; isolating and continuing", ex);
        }
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
        if (operatingMode() == OperatingMode.COMPRESSED_TIME) {
            try {
                timeMachine.addEvent(cmd, ENGINE_COMMAND_TOPIC, this, rankOf(this), indexOf(this));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } else {
            long now = System.currentTimeMillis();
            liveQueue.offer(TimeEvent.unordered(cmd, ENGINE_COMMAND_TOPIC, this, now, now));
        }
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
