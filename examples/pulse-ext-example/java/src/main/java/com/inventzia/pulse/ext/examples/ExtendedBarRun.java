/*
 * SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
 * Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
 */
package com.inventzia.pulse.ext.examples;

import com.inventzia.pulse.beacon.core.AbstractActor;
import com.inventzia.pulse.beacon.core.AbstractGateway;
import com.inventzia.pulse.beacon.core.ComponentReporter;
import com.inventzia.pulse.beacon.core.Gateway;
import com.inventzia.pulse.beacon.core.GatewayStatus;
import com.inventzia.pulse.beacon.core.MultiClientEngine;
import com.inventzia.pulse.beacon.core.OperatingMode;
import com.inventzia.pulse.beacon.core.RunInfo;
import com.inventzia.pulse.beacon.core.Slf4jReporter;
import com.inventzia.pulse.beacon.core.Topic;
import com.inventzia.pulse.data.datum.Datum;
import com.inventzia.pulse.ext.schemas.ExtendedBar;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Shared run logic behind the two runnable examples,
 * {@link HistoricExtendedBarExample} and {@link RealTimeExtendedBarExample}: the extension's
 * {@link ExtendedBar} flowing through the engine, no Python involved.
 *
 * <p>An in-code source gateway emits a short stream of {@code ExtendedBar} (the ad-hoc datum
 * defined by this package), the engine routes it in event-time order, and a recording actor
 * receives it with every field intact. Because {@code ExtendedBar}'s jar carries a
 * {@code META-INF/services} entry for its {@code DatumTypeProvider}, the engine's registry
 * discovers it through the Service Provider Interface alongside the core provider, with no
 * change to pulse-data or pulse-beacon; the run logs both providers via {@link RunInfo}.
 *
 * <p>This class holds no {@code main}: run one of the two example classes instead. The only
 * difference between them is the time window, which selects {@link OperatingMode#COMPRESSED_TIME}
 * (historical replay) or {@link OperatingMode#REAL_TIME} (paced to the wall clock).
 */
final class ExtendedBarRun {

    private static final ComponentReporter LOG =
            new ComponentReporter("extended-bar-run", Slf4jReporter.shared());

    private static final String SYMB  = "AAPL";
    private static final String TOPIC = "ext.bars";

    /** op, hi, lo, cl, bidVolume, askVolume, tradeCount (mirrors the Python example's rows). */
    private record Bar(BigDecimal op, BigDecimal hi, BigDecimal lo, BigDecimal cl,
                       long bidVolume, long askVolume, long tradeCount) {}

    private static final List<Bar> BARS = List.of(
            new Bar(bd("1.00"), bd("1.20"), bd("0.95"), bd("1.10"), 40, 60, 3),
            new Bar(bd("1.10"), bd("1.30"), bd("1.05"), bd("1.25"), 55, 45, 5),
            new Bar(bd("1.25"), bd("1.28"), bd("1.10"), bd("1.15"), 30, 70, 4));

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    private static ExtendedBar bar(long t, Bar b) {
        return new ExtendedBar(
                SYMB, t, b.op(), b.hi(), b.lo(), b.cl(),
                BigDecimal.valueOf(b.bidVolume() + b.askVolume()),   // vlm
                BigDecimal.valueOf(b.bidVolume()),
                BigDecimal.valueOf(b.askVolume()),
                b.tradeCount(), "XNAS");
    }

    /** The rows we expect the consumer to record, in order (symb|time|cl|tradeCount). */
    private static List<String> expected(long[] times) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < times.length; i++) {
            Bar b = BARS.get(i);
            out.add(SYMB + "|" + times[i] + "|" + b.cl() + "|" + b.tradeCount());
        }
        return out;
    }

    // ------------------------------------------------------------------
    // A clock-driving source gateway that emits the fixed ExtendedBar stream.
    // Same shape as HeartBeatGateway: produces, never receives.
    // ------------------------------------------------------------------
    private static final class ExtendedBarFeed extends AbstractGateway {

        private final Topic<ExtendedBar> topic;
        private final long[] times;

        ExtendedBarFeed(String name, Topic<ExtendedBar> topic, long[] times, long start, long end) {
            super(name, start, end);
            this.topic = topic;
            this.times = times;
            setDriveClock(true);
        }

        @Override
        public void run() {
            initialize();
            connect();
            setStatus(GatewayStatus.STARTED);
            boolean realTime = operatingMode() == OperatingMode.REAL_TIME;
            try {
                for (int i = 0; i < times.length && connected(); i++) {
                    long t = times[i];
                    if (realTime) {
                        long wait = t - System.currentTimeMillis();
                        if (wait > 0) Thread.sleep(wait);
                        if (!connected()) break;
                    }
                    Gateway downstream = subscriberForKey(topic, SYMB);
                    if (downstream != null) {
                        // In compressed time this blocks on the time-machine permit until the
                        // previous bar is consumed, pacing the loop in event-time order.
                        downstream.onEvent(topic, bar(t, BARS.get(i)));
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            disconnect();
            setStatus(GatewayStatus.STOPPED);
        }

        @Override public <P extends Datum> void publish(Topic<P> topic, P payload) {
            throw new UnsupportedOperationException(name() + ": source gateway does not accept publish()");
        }

        @Override public <P extends Datum> void onEvent(Topic<P> topic, P payload) {
            throw new UnsupportedOperationException(name() + ": source gateway does not accept incoming events");
        }
    }

    // ------------------------------------------------------------------
    // A recording actor: keeps what it received and logs each bar's fields.
    // ------------------------------------------------------------------
    private static final class RecordingConsumer extends AbstractActor {

        private final List<String> received = new ArrayList<>();

        RecordingConsumer(String name) { super(name); }

        List<String> received() { return received; }

        @Override
        public <P extends Datum> void onEvent(Topic<P> topic, P payload) {
            ExtendedBar b = (ExtendedBar) payload;
            received.add(b.symb() + "|" + b.timestamp() + "|" + b.cl() + "|" + b.tradeCount());
            log.info("recv " + topic.name() + " " + b.symb() + "@" + b.timestamp()
                    + " cl=" + b.cl() + " bidVol=" + b.bidVolume() + " askVol=" + b.askVolume()
                    + " n=" + b.tradeCount());
        }
    }

    private static void awaitStarted(Gateway gateway, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (gateway.status().ordinal() < GatewayStatus.STARTED.ordinal()
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
    }

    /**
     * Assembles and runs the ExtendedBar flow. When {@code realTime} the window spans "now" so the
     * engine selects {@link OperatingMode#REAL_TIME}; otherwise the window is entirely in the past
     * and the run is a deterministic {@link OperatingMode#COMPRESSED_TIME} replay.
     *
     * @return {@code true} if the run completed and the consumer recorded exactly the expected bars
     */
    static boolean execute(boolean realTime) throws Exception {
        long start, end;
        long[] times;
        if (realTime) {
            long base = System.currentTimeMillis() + 1_500L;   // window spans now -> REAL_TIME
            times = new long[] {base, base + 800, base + 1_600};
            start = base - 500;
            end   = base + 5_000;
        } else {
            long s = 1_283_630_000_000L;                       // window in the past -> COMPRESSED_TIME
            times = new long[] {s, s + 1_000, s + 2_000};
            start = s;
            end   = s + 5_000;
        }

        Topic<ExtendedBar> bars = new Topic<>(TOPIC, ExtendedBar.class);
        MultiClientEngine engine = new MultiClientEngine("Engine", start, end);

        ExtendedBarFeed feed = new ExtendedBarFeed("bar-feed", bars, times, start, end);
        RecordingConsumer consumer = new RecordingConsumer("consumer");

        engine.registerPublisher(feed, bars, List.of(SYMB));
        engine.registerActor(consumer, Map.of(bars, List.of(SYMB)), Map.of());

        Thread engineThread = new Thread(engine, "engine");
        engineThread.start();
        awaitStarted(engine, 2_000);
        Thread feedThread = new Thread(feed, "bar-feed");
        feedThread.start();

        engineThread.join(20_000);
        feedThread.join(2_000);

        // Observer surface: what the run did, including the discovered providers. Actors never
        // see this; a strategy cannot tell replay from live.
        RunInfo info = engine.runInfo();
        List<String> received = consumer.received();
        boolean ok = engine.status() == GatewayStatus.COMPLETE && received.equals(expected(times));

        LOG.info("mode=" + info.mode()
                + " window=[" + info.startTime() + ".." + info.endTime() + "]"
                + " typeFingerprint=" + info.typeFingerprint()
                + " providers=" + info.providerIds()
                + " status=" + engine.status() + "; received " + received.size() + " bars");
        for (String r : received) {
            System.out.println("   " + r);
        }
        System.out.println(ok ? "EXTENDED BAR RUN OK" : "EXTENDED BAR RUN FAILED");
        return ok;
    }

    private ExtendedBarRun() {}
}
