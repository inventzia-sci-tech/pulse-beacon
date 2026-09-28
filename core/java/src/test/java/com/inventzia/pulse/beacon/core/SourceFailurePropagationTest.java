/*
 * SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
 * Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
 *
 * This file is part of pulse-beacon.
 *
 * pulse-beacon is dual-licensed:
 *   - Under the GNU Affero General Public License v3.0 (see LICENSE-AGPL-3.0).
 *   - Under a commercial license (see LICENSE-COMMERCIAL.txt).
 *     Contact operations@inventzia.com.
 */
package com.inventzia.pulse.beacon.core;

import com.inventzia.pulse.beacon.core.examples.RunUtils;
import com.inventzia.pulse.beacon.core.gateway.file.JsonlReaderGateway;
import com.inventzia.pulse.data.schemas.platform.HeartBeat;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A source that dies mid-replay must not be mistaken for one that finished.
 *
 * <p>The hole these close: a failing gateway has to disconnect so the TimeMachine's all-drivers
 * barrier is released and the run does not hang — but that is <em>exactly</em> what a healthy gateway
 * does at the end of its stream. The engine's own execution path never threw, so it completed normally
 * and a replay that stopped halfway was recorded as {@code runStatus: completed}.
 *
 * <p>See {@code docs/pulse-sql-gateway.md} §8.
 */
class SourceFailurePropagationTest {

    private static final long START = 1_283_630_000_000L;
    private static final long END   = 1_283_630_007_000L;

    private static final Topic<HeartBeat> HEARTBEAT = new Topic<>("heartbeat", HeartBeat.class);

    /** A JSONL file with one good beat then a line that cannot be parsed. */
    private Path truncatedFile() throws Exception {
        Path file = Files.createTempFile("source-failure", ".jsonl");
        file.toFile().deleteOnExit();
        Files.write(file, List.of(
                "{\"beatKey\":\"BEAT\",\"beatTime\":" + (START + 1_000) + "}",
                "this is not valid json"));
        return file;
    }

    /** A JSONL file that ends cleanly. */
    private Path healthyFile() throws Exception {
        Path file = Files.createTempFile("source-clean", ".jsonl");
        file.toFile().deleteOnExit();
        Files.write(file, List.of(
                "{\"beatKey\":\"BEAT\",\"beatTime\":" + (START + 1_000) + "}",
                "{\"beatKey\":\"BEAT\",\"beatTime\":" + (START + 2_000) + "}"));
        return file;
    }

    /** Run an engine with one reader, returning the outcome the listener saw. */
    private RunOutcome runWith(Path file, boolean fatal, Capturer capturer) throws Exception {
        MultiClientEngine engine = new MultiClientEngine("engine", START, END);
        JsonlReaderGateway<HeartBeat> reader =
                new JsonlReaderGateway<>("Reader", HEARTBEAT, List.of("BEAT"), file, START, END);
        reader.setFailureIsFatal(fatal);

        AtomicReference<RunOutcome> seen = new AtomicReference<>();
        engine.addRunListener(new RunListener() {
            @Override public void onRunTerminated(RunOutcome outcome) { seen.set(outcome); }
        });

        engine.registerPublisher(reader, HEARTBEAT, List.of("BEAT"));
        engine.registerActor(capturer, Map.of(HEARTBEAT, List.of("BEAT")), Map.of());

        Thread engineThread = new Thread(engine, "engine");
        engineThread.start();
        RunUtils.awaitStatus(engine, GatewayStatus.STARTED, 2_000);
        new Thread(reader, "reader").start();
        engineThread.join(10_000);

        assertThat(engineThread.isAlive()).as("the barrier was still released; the run did not hang")
                .isFalse();
        return seen.get();
    }

    // ------------------------------------------------------------------
    // The failure must reach the engine
    // ------------------------------------------------------------------

    @Test
    void aFatalSourceFailureFailsTheRunAndNamesTheGateway() throws Exception {
        RunOutcome outcome = runWith(truncatedFile(), true, new Capturer("cap"));

        assertThat(outcome).isNotNull();
        assertThat(outcome.runStatus())
                .as("a result derived from partial history must not be reported as completed")
                .isEqualTo("failed");
        assertThat(outcome.completed()).isFalse();
        assertThat(outcome.failedGateways()).containsExactly("Reader");
        assertThat(outcome.describe()).contains("Reader").contains("failed reading");
    }

    @Test
    void aNonFatalSourceFailureIsStillRecordedRatherThanLost() throws Exception {
        // The default keeps the long-standing behaviour: the run finishes. What changes is that the
        // failure is no longer invisible - it is in the outcome, for the manifest to carry.
        RunOutcome outcome = runWith(truncatedFile(), false, new Capturer("cap"));

        assertThat(outcome).isNotNull();
        assertThat(outcome.gatewayFailures()).hasSize(1);
        assertThat(outcome.gatewayFailures().get(0).gatewayName()).isEqualTo("Reader");
        assertThat(outcome.gatewayFailures().get(0).fatal()).isFalse();
        assertThat(outcome.fatalFailures()).isEmpty();
        assertThat(outcome.runStatus()).as("not fatal, so the run still completed")
                .isEqualTo("completed");
    }

    @Test
    void acleanEndOfStreamReportsNoFailureAtAll() throws Exception {
        // The control. Without it, every one of these tests would pass just as well if the engine
        // reported failure unconditionally - and end-of-stream disconnects look identical.
        Capturer capturer = new Capturer("cap");
        RunOutcome outcome = runWith(healthyFile(), true, capturer);

        assertThat(outcome).isNotNull();
        assertThat(outcome.gatewayFailures()).isEmpty();
        assertThat(outcome.runStatus()).isEqualTo("completed");
        assertThat(outcome.completed()).isTrue();
        assertThat(capturer.times).containsExactly(START + 1_000, START + 2_000);
    }

    @Test
    void eventsBeforeTheFailureAreStillDelivered() throws Exception {
        // Failing the run is about the verdict, not about discarding what was already dispatched.
        Capturer capturer = new Capturer("cap");
        runWith(truncatedFile(), true, capturer);
        assertThat(capturer.times).containsExactly(START + 1_000);
    }

    // ------------------------------------------------------------------
    // The gateway's own record
    // ------------------------------------------------------------------

    @Test
    void theGatewayRecordsItsOwnTerminalFailure() throws Exception {
        Path file = truncatedFile();
        JsonlReaderGateway<HeartBeat> reader =
                new JsonlReaderGateway<>("Reader", HEARTBEAT, List.of("BEAT"), file, START, END);
        reader.setFailureIsFatal(true);
        MultiClientEngine engine = new MultiClientEngine("engine", START, END);
        engine.registerPublisher(reader, HEARTBEAT, List.of("BEAT"));

        Thread engineThread = new Thread(engine, "engine");
        engineThread.start();
        RunUtils.awaitStatus(engine, GatewayStatus.STARTED, 2_000);
        new Thread(reader, "reader").start();
        engineThread.join(10_000);

        assertThat(reader.terminalFailure()).isPresent();
        assertThat(reader.terminalFailure().get().gatewayName()).isEqualTo("Reader");
        assertThat(reader.terminalFailure().get().fatal()).isTrue();
        assertThat(reader.terminalFailure().get().cause()).isNotNull();
        // The status is still STOPPED - which is precisely why it cannot carry this information.
        assertThat(reader.status()).isEqualTo(GatewayStatus.STOPPED);
    }

    @Test
    void aHealthyGatewayHasNoTerminalFailure() throws Exception {
        JsonlReaderGateway<HeartBeat> reader = new JsonlReaderGateway<>(
                "Reader", HEARTBEAT, List.of("BEAT"), healthyFile(), START, END);
        MultiClientEngine engine = new MultiClientEngine("engine", START, END);
        engine.registerPublisher(reader, HEARTBEAT, List.of("BEAT"));

        Thread engineThread = new Thread(engine, "engine");
        engineThread.start();
        RunUtils.awaitStatus(engine, GatewayStatus.STARTED, 2_000);
        new Thread(reader, "reader").start();
        engineThread.join(10_000);

        assertThat(reader.terminalFailure()).isEmpty();
        assertThat(reader.status()).as("the same terminal status as the failing reader")
                .isEqualTo(GatewayStatus.STOPPED);
    }

    // ------------------------------------------------------------------
    // The record itself
    // ------------------------------------------------------------------

    @Test
    void theFirstFailureIsTheOneKept() {
        // Later errors are usually consequences of the first; the first is what explains the run.
        var gateway = new TestGateway("G");
        gateway.setFailureIsFatal(true);
        gateway.fail("the real cause", new IllegalStateException("first"));
        gateway.fail("a consequence", new IllegalStateException("second"));

        assertThat(gateway.terminalFailure()).isPresent();
        assertThat(gateway.terminalFailure().get().detail()).isEqualTo("the real cause");
    }

    @Test
    void aFailureListenerSeesTheFailureWithItsFatalFlag() {
        var gateway = new TestGateway("G");
        gateway.setFailureIsFatal(true);
        List<GatewayFailure> seen = Collections.synchronizedList(new ArrayList<>());
        gateway.addFailureListener(seen::add);

        gateway.fail("boom", null);

        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).gatewayName()).isEqualTo("G");
        assertThat(seen.get(0).fatal()).isTrue();
        assertThat(seen.get(0).describe()).contains("G").contains("boom");
    }

    @Test
    void aThrowingListenerCannotStopTheOthers() {
        var gateway = new TestGateway("G");
        List<GatewayFailure> seen = Collections.synchronizedList(new ArrayList<>());
        gateway.addFailureListener(f -> { throw new RuntimeException("listener is broken"); });
        gateway.addFailureListener(seen::add);

        gateway.fail("boom", null);
        assertThat(seen).as("one bad listener must not swallow the failure").hasSize(1);
    }

    @Test
    void aRunOutcomeWithNoFailuresBehavesAsBefore() {
        // The three-argument form still means what it always meant.
        RunOutcome clean = new RunOutcome(GatewayStatus.COMPLETE, null, false);
        assertThat(clean.completed()).isTrue();
        assertThat(clean.runStatus()).isEqualTo("completed");
        assertThat(clean.gatewayFailures()).isEmpty();
    }

    // ------------------------------------------------------------------
    // The abort must actually cut the run short, not merely relabel it
    // ------------------------------------------------------------------

    @Test
    void aFatalFailureCancelsTheOtherSourcesRatherThanLettingThemReadOn() throws Exception {
        // Section 8's third requirement, and the one the outcome alone cannot demonstrate: a run that
        // is already doomed must stop, not finish reading into a result nobody can use. With two
        // sources, the survivor's delivered events are the evidence.
        Path dying = truncatedFile();
        Path long_ = Files.createTempFile("source-long", ".jsonl");
        long_.toFile().deleteOnExit();
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            // Spread across the window so the run has to keep dispatching to get through them.
            lines.add("{\"beatKey\":\"OTHER\",\"beatTime\":" + (START + i * 1_000L) + "}");
        }
        Files.write(long_, lines);

        MultiClientEngine engine = new MultiClientEngine("engine", START, END);
        JsonlReaderGateway<HeartBeat> failing = new JsonlReaderGateway<>(
                "Failing", HEARTBEAT, List.of("BEAT"), dying, START, END);
        failing.setFailureIsFatal(true);
        JsonlReaderGateway<HeartBeat> survivor = new JsonlReaderGateway<>(
                "Survivor", HEARTBEAT, List.of("OTHER"), long_, START, END);

        AtomicReference<RunOutcome> seen = new AtomicReference<>();
        engine.addRunListener(new RunListener() {
            @Override public void onRunTerminated(RunOutcome outcome) { seen.set(outcome); }
        });
        Capturer capturer = new Capturer("cap");
        engine.registerPublisher(failing, HEARTBEAT, List.of("BEAT"));
        engine.registerPublisher(survivor, HEARTBEAT, List.of("OTHER"));
        engine.registerActor(capturer,
                Map.of(HEARTBEAT, List.of("BEAT", "OTHER")), Map.of());

        Thread engineThread = new Thread(engine, "engine");
        engineThread.start();
        RunUtils.awaitStatus(engine, GatewayStatus.STARTED, 2_000);
        new Thread(failing, "failing").start();
        new Thread(survivor, "survivor").start();
        engineThread.join(10_000);

        assertThat(engineThread.isAlive()).isFalse();
        assertThat(seen.get()).isNotNull();
        assertThat(seen.get().runStatus()).isEqualTo("failed");
        // The engine must end STOPPED: it refused to call this a completed run. If the abort did not
        // happen, the engine would reach COMPLETE and simply be relabelled by the outcome.
        assertThat(engine.status()).as("the run was actually aborted, not just reported as failed")
                .isEqualTo(GatewayStatus.STOPPED);
    }

    @Test
    void anOutcomeIsNotCompletedWhenAGatewayFailedFatallyEvenIfTheEngineItselfFinished() {
        // Guards completed() on its own terms. The engine reaching COMPLETE with no error of its own
        // is exactly the shape of the original bug: the failure lived entirely in the gateway.
        RunOutcome outcome = new RunOutcome(GatewayStatus.COMPLETE, null, false,
                List.of(new GatewayFailure("Reader", "died mid-replay", null,
                        System.currentTimeMillis(), true)));

        assertThat(outcome.completed())
                .as("a clean engine path does not make a half-finished replay a success").isFalse();
        assertThat(outcome.runStatus()).isEqualTo("failed");
        assertThat(outcome.failedGateways()).containsExactly("Reader");
    }

    @Test
    void aNonFatalGatewayFailureLeavesTheOutcomeCompleted() {
        // The other side of the same boundary, so the check above cannot pass by refusing everything.
        RunOutcome outcome = new RunOutcome(GatewayStatus.COMPLETE, null, false,
                List.of(new GatewayFailure("Sink", "could not write", null,
                        System.currentTimeMillis(), false)));

        assertThat(outcome.completed()).isTrue();
        assertThat(outcome.runStatus()).isEqualTo("completed");
        assertThat(outcome.gatewayFailures()).as("still recorded, just not fatal").hasSize(1);
    }

    /** Collects the event times actually dispatched, so "what got through" is checkable. */
    private static final class Capturer extends AbstractActor {
        final List<Long> times = Collections.synchronizedList(new ArrayList<>());

        Capturer(String name) { super(name); }

        @Override
        public <P extends com.inventzia.pulse.data.datum.Datum> void onEvent(
                Topic<P> topic, P payload) {
            times.add(payload.getDatumTime());
        }
    }

    /** A gateway that exists only to expose {@code failTerminally}. */
    private static final class TestGateway extends AbstractGateway {
        TestGateway(String name) { super(name, START, END); }
        void fail(String detail, Throwable cause) { failTerminally(detail, cause); }
        @Override public void run() { }
        @Override public <P extends com.inventzia.pulse.data.datum.Datum> void onEvent(
                Topic<P> topic, P payload) { }
        @Override public <P extends com.inventzia.pulse.data.datum.Datum> void publish(
                Topic<P> topic, P payload) { }
    }
}
