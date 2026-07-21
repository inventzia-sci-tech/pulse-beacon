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
package com.inventzia.pulse.beacon.core.examples;

import com.inventzia.pulse.beacon.core.ComponentReporter;
import com.inventzia.pulse.beacon.core.GatewayStatus;
import com.inventzia.pulse.beacon.core.MultiClientEngine;
import com.inventzia.pulse.beacon.core.Slf4jReporter;
import com.inventzia.pulse.beacon.core.Topic;
import com.inventzia.pulse.beacon.core.crosslanguage.CrossLanguageActor;
import com.inventzia.pulse.beacon.core.crosslanguage.JepLauncher;
import com.inventzia.pulse.beacon.core.gateway.periodic.HeartBeatGateway;
import com.inventzia.pulse.data.schemas.platform.HeartBeat;
import com.inventzia.pulse.data.schemas.platform.TextMessage;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.inventzia.pulse.beacon.core.crosslanguage.JepLauncher.Loop.CONSUME;

/**
 * Java-host (JEP) re-creation of {@code realtime_run_jpype.py} — the real-time
 * mirror of {@link HistoricRunJepExample}, and the opposite embedding direction
 * of the JPype real-time run.
 *
 * <p>Three Java heartbeat gateways (3 s, 6 s, 10 s), each on its own topic, drive a
 * near-future 30-second wall-clock window, so the engine runs in REAL_TIME. Two
 * <em>Python</em> actors, hosted under the JVM via {@link JepLauncher}, consume the
 * beats: a printer (records into a shared Java list so the host can count) and an
 * echo actor that re-publishes each beat as a {@code TextMessage} on an {@code echo}
 * topic, which a Java {@code PrintGateway} sink consumes — data crossing the
 * boundary both ways in one live run:
 *
 * <pre>
 *   beater-3s  (heartbeat.3s) ─┐                               ┌▶ Py printer (records)
 *   beater-6s  (heartbeat.6s) ─┼─▶ [ Java engine / live queue ]┤
 *   beater-10s (heartbeat.10s)─┘                               └▶ Py echo ─▶ (echo) ─▶ Java PrintGateway
 * </pre>
 *
 * <p>Unlike the historical run this is wall-clock paced (it takes about as long as
 * its window, ~30 s) and is a live demo, not a deterministic replay — so it asserts
 * only that the engine reaches COMPLETE and the Python printer saw beats, not exact
 * parity. The three cadences interleave in the console and coincide at their common
 * multiples. The Python components reuse the same types as the JPype run.
 *
 * <p>Run: see {@code docs/cross-language-python-inprocess.md} §8b and
 * {@code run-jep-example.sh} (swap the main class), or the JEP setup {@code JEP_README.md}.
 */
public final class RealTimeRunJepExample {

    private static final ComponentReporter LOG =
            new ComponentReporter("realtime-run-jep", Slf4jReporter.shared());

    private static final long WINDOW_MS = 30_000L; // 30-second run, mirroring the Java example
    // Start a little ahead of "now" so the heartbeats land in the future and the run is
    // wall-clock paced. Overshooting startTime is harmless (lenient mode selection treats a
    // now-inside-window run as REAL_TIME, not the throwing MIXED) — this is for demo timing,
    // and also gives the JEP interpreters time to spin up before dispatch.
    private static final long LEAD_MS = 8_000L;

    // The two packages' src/ roots for the interpreters' sys.path (public inventzia.pulse.*
    // imports); redundant when the packages are pip-installed, but harmless.
    private static final Path REPO_ROOT =
            Paths.get(System.getProperty("pulse.repo.root", System.getProperty("user.dir")));
    private static final String BEACON_ROOT = REPO_ROOT.resolve("pulse-beacon").resolve("src").toString();
    private static final String DATA_ROOT   = REPO_ROOT.resolve("pulse-data").resolve("src").toString();

    private static final String RECORDING_PRINTER =
            "inventzia.pulse.beacon.core.examples.recording_print_consumer.RecordingPrinter";
    private static final String ECHO_CONSUMER =
            "inventzia.pulse.beacon.core.examples.echo_consumer.EchoConsumer";

    private RealTimeRunJepExample() {
    }

    public static void main(String[] args) throws Exception {
        long now   = System.currentTimeMillis();
        long start = now + LEAD_MS;
        long end   = start + WINDOW_MS;

        // --- topics: one per cadence, plus the echo topic -------------------
        Topic<HeartBeat>   fast   = new Topic<>("heartbeat.3s",  HeartBeat.class);
        Topic<HeartBeat>   medium = new Topic<>("heartbeat.6s",  HeartBeat.class);
        Topic<HeartBeat>   slow   = new Topic<>("heartbeat.10s", HeartBeat.class);
        Topic<TextMessage> echo   = new Topic<>("echo",          TextMessage.class);

        // --- engine ---------------------------------------------------------
        MultiClientEngine engine = new MultiClientEngine("engine", start, end);

        // --- Java sources (clock-driving, paced to wall clock) + echo sink --
        HeartBeatGateway beater3  = new HeartBeatGateway("beater-3s",  fast,   "3S",   3_000L, start, start, end);
        HeartBeatGateway beater6  = new HeartBeatGateway("beater-6s",  medium, "6S",   6_000L, start, start, end);
        HeartBeatGateway beater10 = new HeartBeatGateway("beater-10s", slow,   "10S", 10_000L, start, start, end);
        PrintGateway echoSink = new PrintGateway("echo-sink", start, end);

        // --- Java halves of the Python actors -------------------------------
        CrossLanguageActor pyPrint = new CrossLanguageActor("py-print");
        CrossLanguageActor pyEcho  = new CrossLanguageActor("py-echo").withTopic(echo);

        // --- registration (mirrors RealTimeHeartbeatExample + echo round-trip)
        engine.registerPublisher(beater3,  fast,   List.of("3S"));
        engine.registerPublisher(beater6,  medium, List.of("6S"));
        engine.registerPublisher(beater10, slow,   List.of("10S"));
        engine.registerActor(pyPrint,
                Map.of(fast, List.of("3S"), medium, List.of("6S"), slow, List.of("10S")),
                Map.of());
        engine.registerActor(pyEcho,
                Map.of(fast, List.of("3S"), medium, List.of("6S"), slow, List.of("10S")),
                Map.of(echo, List.of("ECHO")));
        engine.registerSubscriber(echoSink, echo, List.of("ECHO"));

        // The Python printer appends here; the host counts beats seen (cross-thread-safe).
        List<String> received = Collections.synchronizedList(new ArrayList<>());

        // --- Python components, declared as data and run under the JVM via JEP ---
        JepLauncher jep = new JepLauncher(List.of(BEACON_ROOT, DATA_ROOT));

        JepLauncher.Component printer = new JepLauncher.Component("py-print-streamer", pyPrint, CONSUME,
                RECORDING_PRINTER, List.of("print-actor", received));
        JepLauncher.Component echoer = new JepLauncher.Component("py-echo-streamer", pyEcho, CONSUME,
                ECHO_CONSUMER, List.of("echo-actor", "echo", "ECHO"), true); // Actor that publishes

        // Consume components start before the engine (the startup barrier waits for them
        // to be ready, so nothing is dispatched before the interpreters spin up).
        Thread tPrint = jep.launch(printer);
        Thread tEcho = jep.launch(echoer);
        tPrint.start();
        tEcho.start();

        // --- engine, then release the wall-clock sources --------------------
        Thread engineThread = new Thread(engine, "engine");
        engineThread.start();
        RunUtils.awaitStatus(engine, GatewayStatus.STARTED, 5_000);

        new Thread(beater3,  "beater-3s").start();
        new Thread(beater6,  "beater-6s").start();
        new Thread(beater10, "beater-10s").start();
        new Thread(echoSink, "echo-sink").start();

        engineThread.join(LEAD_MS + WINDOW_MS + 10_000L);
        tPrint.join(5_000);
        tEcho.join(5_000);

        String status = engine.status().toString();
        int beats = received.size();
        LOG.info("finished — engine status: " + status + "; printer received " + beats + " beats");

        boolean ok = engine.status() == GatewayStatus.COMPLETE && beats > 0;
        if (!ok) {
            LOG.severe("run did not complete cleanly: status=" + status + ", beats=" + beats);
        }
        System.out.println(ok ? "REALTIME OK" : "REALTIME FAILED");
        System.exit(ok ? 0 : 1);
    }
}
