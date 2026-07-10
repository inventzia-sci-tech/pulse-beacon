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
import com.inventzia.pulse.beacon.core.crosslanguage.CrossLanguageGateway;
import com.inventzia.pulse.beacon.core.crosslanguage.JepLauncher;
import com.inventzia.pulse.beacon.core.gateway.file.JsonlReaderGateway;
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
import static com.inventzia.pulse.beacon.core.crosslanguage.JepLauncher.Loop.PRODUCE;

/**
 * Java-host re-creation of {@link HistoricRunExample}, the mirror of
 * {@code historic_run_jpype.py} for the opposite embedding direction.
 *
 * <p>Here the <b>JVM is the host</b>: it owns the engine and runs the Python
 * components under the JVM via {@link JepLauncher}. The example declares each
 * Python component as data — its Java {@code CrossLanguage} endpoint, streamer
 * loop, Python type, and constructor args — and the launcher opens one JEP
 * interpreter per component and drives its streamer loop. Same Java core, same
 * Python components, same streamer as the JPype run — only the bootstrap differs.
 *
 * <pre>
 *   py-feed (stream.one) ─┐   [Python source, JEP]
 *   reader-2 (stream.two)─┼─▶ [ Java engine / time machine ] ─▶ Py PrintConsumer  [JEP]
 *   beater   (heartbeat) ─┘                                  └▶ Py EchoConsumer ─▶ (echo) ─▶ PrintGateway
 * </pre>
 *
 * <p>The Python printer records what it receives into a shared Java list, so the
 * host can assert it saw exactly the merged, event-time-ordered sequence the
 * all-Java run produces.
 *
 * <p>Run (needs JEP's jar on the classpath and its native lib + libpython on the
 * library path — see {@code docs/cross-language-python-inprocess.md} §8b and
 * {@code run-jep-example.sh}). The two Python import roots come from
 * {@code -Dpulse.repo.root} (defaults to the working directory), expected to
 * contain {@code pulse-beacon} and {@code pulse-data}.
 */
public final class HistoricRunJepExample {

    private static final ComponentReporter LOG =
            new ComponentReporter("historic-run-jep", Slf4jReporter.shared());

    private static final long START = 1_283_630_000_000L;
    private static final long END   = 1_283_630_007_000L;

    private static final List<String> EXPECTED = List.of(
            "stream.one@1283630000000",
            "stream.two@1283630001000",
            "heartbeat@1283630001500",
            "stream.one@1283630002000",
            "stream.two@1283630003000",
            "heartbeat@1283630003500",
            "stream.one@1283630004000",
            "stream.two@1283630005000",
            "heartbeat@1283630005500",
            "stream.one@1283630006000");

    /** Repo roots for the interpreters' sys.path (repo-root-prefixed imports); handed to the launcher. */
    private static final Path REPO_ROOT =
            Paths.get(System.getProperty("pulse.repo.root", System.getProperty("user.dir")));
    private static final String BEACON_ROOT = REPO_ROOT.resolve("pulse-beacon").toString();
    private static final String DATA_ROOT   = REPO_ROOT.resolve("pulse-data").toString();

    // The Python component types this run wires up. The Java host names the type;
    // the JEP factory (jep_host) builds it.
    private static final String RECORDING_PRINTER =
            "core.python.inventzia.pulse.beacon.core.examples.recording_print_consumer.RecordingPrinter";
    private static final String ECHO_CONSUMER =
            "core.python.inventzia.pulse.beacon.core.examples.echo_consumer.EchoConsumer";
    private static final String MESSAGE_FEED_GATEWAY =
            "core.python.inventzia.pulse.beacon.core.examples.message_feed_gateway.MessageFeedGateway";

    private HistoricRunJepExample() {
    }

    public static void main(String[] args) throws Exception {
        // --- topics ---------------------------------------------------------
        Topic<TextMessage> streamOne = new Topic<>("stream.one", TextMessage.class);
        Topic<TextMessage> streamTwo = new Topic<>("stream.two", TextMessage.class);
        Topic<HeartBeat>   heartbeat = new Topic<>("heartbeat",  HeartBeat.class);
        Topic<TextMessage> echo      = new Topic<>("echo",       TextMessage.class);

        // --- engine ---------------------------------------------------------
        MultiClientEngine engine = new MultiClientEngine("engine", START, END);

        // --- Java sources / sink --------------------------------------------
        JsonlReaderGateway<TextMessage> readerTwo = new JsonlReaderGateway<>(
                "reader-2", streamTwo, List.of("A"),
                RunUtils.resource("/examples/data/messages_two.jsonl"), START, END);
        HeartBeatGateway beater = new HeartBeatGateway(
                "beater", heartbeat, "BEAT", 2_000L, START + 1_500L, START, END);
        PrintGateway echoSink = new PrintGateway("echo-sink", START, END);

        // --- Java halves of the Python components ---------------------------
        CrossLanguageGateway pyFeed  = new CrossLanguageGateway("py-feed", START, END).withTopic(streamOne);
        CrossLanguageActor   pyPrint = new CrossLanguageActor("py-print");
        CrossLanguageActor   pyEcho  = new CrossLanguageActor("py-echo").withTopic(echo);

        // --- registration (mirrors HistoricRunExample) ----------------------
        engine.registerPublisher(pyFeed,    streamOne, List.of("A"));
        engine.registerPublisher(readerTwo, streamTwo, List.of("A"));
        engine.registerPublisher(beater,    heartbeat, List.of("BEAT"));
        engine.registerActor(pyPrint,
                Map.of(streamOne, List.of("A"),
                       streamTwo, List.of("A"),
                       heartbeat, List.of("BEAT")),
                Map.of());
        engine.registerActor(pyEcho,
                Map.of(streamOne, List.of("A")),
                Map.of(echo, List.of("ECHO")));
        engine.registerSubscriber(echoSink, echo, List.of("ECHO"));

        // Parity sink: the Python printer appends "topic@time" here (cross-thread-safe).
        List<String> received = Collections.synchronizedList(new ArrayList<>());
        String feedData = RunUtils.resource("/examples/data/messages_one.jsonl").toString();

        // --- Python components, declared as data and run under the JVM via JEP ---
        JepLauncher jep = new JepLauncher(List.of(BEACON_ROOT, DATA_ROOT));

        JepLauncher.Component printer = new JepLauncher.Component("py-print-streamer", pyPrint, CONSUME,
                RECORDING_PRINTER, List.of("print-actor", received));
        JepLauncher.Component echoer = new JepLauncher.Component("py-echo-streamer", pyEcho, CONSUME,
                ECHO_CONSUMER, List.of("echo-actor", "echo", "ECHO"), true); // Actor that publishes
        JepLauncher.Component feed = new JepLauncher.Component("py-feed-streamer", pyFeed, PRODUCE,
                MESSAGE_FEED_GATEWAY, List.of("feed", "stream.one", feedData));

        // Consume components start before the engine, so they are draining before dispatch.
        Thread tPrint = jep.launch(printer);
        Thread tEcho = jep.launch(echoer);
        tPrint.start();
        tEcho.start();

        // --- engine, then release the sources -------------------------------
        Thread engineThread = new Thread(engine, "engine");
        engineThread.start();
        RunUtils.awaitStatus(engine, GatewayStatus.STARTED, 5_000);

        new Thread(readerTwo, "reader-2").start();
        new Thread(beater,    "beater").start();
        new Thread(echoSink,  "echo-sink").start();
        new Thread(pyFeed,    "py-feed").start();   // Java source loop for the Python feed gateway
        Thread tFeed = jep.launch(feed);            // Python produce loop
        tFeed.start();

        engineThread.join(15_000);
        tFeed.join(5_000);
        tPrint.join(5_000);
        tEcho.join(5_000);

        String status = engine.status().toString();
        LOG.info("engine status: " + status + "; printer received " + received.size() + " events");

        boolean ok = received.equals(EXPECTED) && engine.status() == GatewayStatus.COMPLETE;
        if (!ok) {
            LOG.severe("PARITY MISMATCH\n  expected: " + EXPECTED + "\n  received: " + received
                    + "\n  status: " + status);
        }
        System.out.println(ok ? "PARITY OK" : "PARITY FAILED");
        System.exit(ok ? 0 : 1);
    }
}
