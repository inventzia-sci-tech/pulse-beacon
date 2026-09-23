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
package com.inventzia.pulse.beacon.core.examples;

import com.inventzia.pulse.beacon.core.ComponentReporter;
import com.inventzia.pulse.beacon.core.GatewayStatus;
import com.inventzia.pulse.beacon.core.MultiClientEngine;
import com.inventzia.pulse.beacon.core.RunInfo;
import com.inventzia.pulse.beacon.core.Slf4jReporter;
import com.inventzia.pulse.beacon.core.Topic;
import com.inventzia.pulse.beacon.core.gateway.periodic.HeartBeatGateway;
import com.inventzia.pulse.beacon.core.run.RunLayout;
import com.inventzia.pulse.beacon.core.run.RunRecording;
import com.inventzia.pulse.data.schemas.platform.HeartBeat;
import com.inventzia.pulse.data.schemas.platform.TextMessage;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Runnable example: a long real-time run that writes its recording as it goes.
 *
 * <p>Built to be <em>watched</em> rather than inspected afterwards. It runs for three minutes by
 * default and emits at a deliberately slow, uneven cadence, so a viewer following the recording has
 * something arriving every few seconds and the two streams visibly drift in and out of phase:
 *
 * <ul>
 *   <li>a <b>5-second</b> heartbeat on {@code heartbeat.5s} — roughly 36 beats over the run;</li>
 *   <li>an <b>18-second</b> heartbeat on {@code heartbeat.18s} — roughly 10 beats;</li>
 *   <li>an {@link EchoConsumer} subscribed to the 18-second beat only, publishing a
 *       {@link TextMessage} back into the stream on {@code echo.18s} for each one it receives.</li>
 * </ul>
 *
 * <p>The echo is the interesting part for a viewer: it is a <em>derived</em> event, published by an
 * actor rather than sourced by a gateway, and it is re-stamped at the triggering beat's time. Two
 * events therefore share an event time, which is exactly the case the dispatch-order tie-break
 * exists to make deterministic. 5 and 18 share only 90, so the two beats coincide twice in a
 * three-minute run.
 *
 * <p>All three routes are recorded into the standardized output layout, so the run appears under
 * {@code $PULSE_OUTPUT/live/RealTimeEchoExample/<runId>/} the moment it starts, with its manifest
 * marked {@code running} until it finishes. A viewer opening it mid-run sees it as live and follows
 * it.
 *
 * <p>Usage: {@code RealTimeEchoExample [seconds] [outputRoot]} — the run length (default 180) and
 * where to write. The output root may also come from {@code $PULSE_OUTPUT}; given neither, runs land
 * in {@code ~/.pulse/runs}, which is easy to miss when launching from an IDE that does not pass the
 * environment through.
 */
public final class RealTimeEchoExample {

    private static final ComponentReporter LOG =
            new ComponentReporter("Realtime-echo", Slf4jReporter.shared());

    private static final String APP = "RealTimeEchoExample";

    private static final long DEFAULT_SECONDS = 180L;   // three minutes
    private static final long FAST_PERIOD_MS  = 5_000L;
    private static final long SLOW_PERIOD_MS  = 18_000L;

    private RealTimeEchoExample() {
    }

    public static void main(String[] args) throws Exception {
        long seconds = DEFAULT_SECONDS;
        if (args.length > 0) {
            try {
                seconds = Math.max(10L, Long.parseLong(args[0].trim()));
            } catch (NumberFormatException e) {
                System.err.println("usage: RealTimeEchoExample [seconds] [outputRoot]"
                        + "   (default " + DEFAULT_SECONDS + "s, $PULSE_OUTPUT, ~/.pulse/runs)");
                return;
            }
        }
        // An explicit root beats $PULSE_OUTPUT, which beats the default. Passing it as an argument
        // is usually easier than adding an environment variable to an IDE launch configuration.
        Path root = args.length > 1 && !args[1].isBlank() ? Path.of(args[1].trim()) : null;

        // Start a little ahead of now so every beat lands in the future and the run is wall-clock
        // paced from the first beat rather than firing a burst at startup.
        final long start = System.currentTimeMillis() + 3_000L;
        final long end   = start + seconds * 1_000L;

        Topic<HeartBeat>   fast = new Topic<>("heartbeat.5s",  HeartBeat.class);
        Topic<HeartBeat>   slow = new Topic<>("heartbeat.18s", HeartBeat.class);
        Topic<TextMessage> echo = new Topic<>("echo.18s",      TextMessage.class);

        MultiClientEngine engine = new MultiClientEngine("Engine", start, end);

        HeartBeatGateway beaterFast = new HeartBeatGateway(
                "Beater-5s", fast, "5S", FAST_PERIOD_MS, start, start, end);
        HeartBeatGateway beaterSlow = new HeartBeatGateway(
                "Beater-18s", slow, "18S", SLOW_PERIOD_MS, start, start, end);

        engine.registerPublisher(beaterFast, fast, List.of("5S"));
        engine.registerPublisher(beaterSlow, slow, List.of("18S"));

        // Subscribes to the slow beat only, and publishes its echo back into the engine. The second
        // map is the actor's publications: without it the echo would have nowhere to be routed.
        EchoConsumer echoActor = new EchoConsumer("Echo", echo, "ECHO");
        engine.registerActor(echoActor,
                Map.of(slow, List.of("18S")),
                Map.of(echo, List.of("ECHO")));

        RunInfo info;
        try (RunRecording run = RunRecording.start(APP, engine, start, end,
                "engine:" + engine.name(), 1 << 16, root)) {

            // Record all three routes. The recorder is the only subscriber *sink* on each; the echo
            // actor is an actor, not a sink, so it does not contend for the slow route (Stage A).
            run.recordRoute(engine, fast, List.of("5S"));
            run.recordRoute(engine, slow, List.of("18S"));
            run.recordRoute(engine, echo, List.of("ECHO"));

            // The run's own lifecycle, in the same stream as its data: every transition of the
            // engine and both beaters arrives as an ordinary event on the status topic.
            run.recordStatus(engine, beaterFast, beaterSlow);

            // Run-scoped threads, so everything these log lands in this run's console.log.
            Thread engineThread = run.scoped(engine, "engine");
            engineThread.start();
            RunUtils.awaitStatus(engine, GatewayStatus.STARTED, 5_000);

            RunLayout.RunPaths paths = run.paths();
            System.out.println("run output: " + (paths == null ? "(not created)" : paths.dir()));
            System.out.println("following: open this run in the viewer while it is still going");

            Thread fastThread = run.scoped(beaterFast, "Beater-5s");
            Thread slowThread = run.scoped(beaterSlow, "Beater-18s");
            fastThread.start();
            slowThread.start();

            // Wall-clock paced, so allow the window plus a margin for start-up and drain.
            engineThread.join(seconds * 1_000L + 60_000L);
            fastThread.join(5_000L);
            slowThread.join(5_000L);

            info = engine.runInfo();
            LOG.info("mode=" + info.mode()
                    + " window=[" + info.startTime() + ".." + info.endTime() + "]"
                    + " status=" + engine.status());
        }

        System.out.println("REAL TIME ECHO RUN "
                + (engine.status() == GatewayStatus.COMPLETE ? "OK" : "FAILED (" + engine.status() + ")"));
    }
}
