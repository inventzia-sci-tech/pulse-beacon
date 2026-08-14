# SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
# Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
"""Shared run logic behind the two runnable scripts, ``historical_extended_bar_run.py``
and ``realtime_extended_bar_run.py``: the extension's ExtendedBar flowing through the Java
engine (JPype).

A Python source gateway emits a short stream of ExtendedBar (the ad-hoc datum defined by
this package), the Java engine routes them, and a Python consumer receives them with all
fields intact. The extension jar is placed on the JVM classpath so Java's ServiceLoader
discovers the same ExtendedBar provider Python discovered via its entry point; the bridge's
type-universe gate then confirms both runtimes agree before any event flows.

This module holds no ``__main__``: run one of the two scripts instead. The only difference
between them is the time window, which selects compressed-time (historical replay) or
real-time (paced to the wall clock) via ``run(realtime=...)``.
"""

import threading
import time
from decimal import Decimal
from pathlib import Path

from inventzia.pulse.beacon.core.actor import BeaconActor
from inventzia.pulse.beacon.core.crosslanguage import jpype_host
from inventzia.pulse.beacon.core.crosslanguage.cross_language_streamer import CrossLanguageStreamer
from inventzia.pulse.beacon.core.gateway import BeaconGateway
from inventzia.pulse.beacon.core.reporter import ComponentReporter, ReportLevel, configure_logging
from inventzia.pulse.ext.schemas.extended_bar import ExtendedBar

LOG = ComponentReporter("extended-bar-run")

_EXT_JAR = Path(__file__).resolve().parents[1] / "java" / "target" / "pulse-ext-example-0.1.0.jar"

SYMB = "AAPL"
_BARS = [
    (Decimal("1.00"), Decimal("1.20"), Decimal("0.95"), Decimal("1.10"), 40, 60, 3),
    (Decimal("1.10"), Decimal("1.30"), Decimal("1.05"), Decimal("1.25"), 55, 45, 5),
    (Decimal("1.25"), Decimal("1.28"), Decimal("1.10"), Decimal("1.15"), 30, 70, 4),
]


def _bar(t, row):
    op, hi, lo, cl, bidv, askv, n = row
    return ExtendedBar(symb=SYMB, timestamp=t, op=op, hi=hi, lo=lo, cl=cl,
                       vlm=bidv + askv, bidVolume=bidv, askVolume=askv, tradeCount=n, venue="XNAS")


class ExtendedBarFeed(BeaconGateway):
    """Source gateway emitting a fixed stream of ExtendedBar."""

    def __init__(self, name, topic_name, times):
        super().__init__(name)
        self._topic_name = topic_name
        self._times = times

    def produce(self):
        for t, row in zip(self._times, _BARS):
            yield self._topic_name, _bar(t, row)


class RecordingConsumer(BeaconActor):
    def __init__(self, name, sink):
        super().__init__(name)
        self._sink = sink

    def on_event(self, topic_name, datum):
        self._sink.append((str(datum.datum_key), int(datum.datum_time),
                           str(datum.cl), int(datum.trade_count)))
        LOG.info(f"recv {topic_name} {datum.datum_key}@{datum.datum_time} "
                 f"cl={datum.cl} bidVol={datum.bid_volume} askVol={datum.ask_volume} n={datum.trade_count}")


def _await_started(engine, timeout_s=5.0):
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        if str(engine.status().name()) == "STARTED":
            return
        time.sleep(0.02)
    raise TimeoutError("engine did not reach STARTED")


def expected(times):
    return [(SYMB, t, str(row[3]), row[6]) for t, row in zip(times, _BARS)]


def run(realtime=False):
    """Run the cross-language ExtendedBar demo; returns ``(received, status, times)``."""
    configure_logging(ReportLevel.INFO)
    if not _EXT_JAR.exists():
        raise FileNotFoundError(f"build the extension jar first: {_EXT_JAR} (mvn -f java/pom.xml package)")

    if realtime:
        base = int(time.time() * 1000) + 1_500          # window spans now → REAL_TIME
        times = [base, base + 800, base + 1_600]
        start, end = base - 500, base + 5_000
    else:
        start = 1_283_630_000_000                        # window in the past → COMPRESSED_TIME
        times = [start, start + 1_000, start + 2_000]
        start, end = start, start + 5_000

    jpype_host.start_jvm(extra_classpath=[_EXT_JAR])     # gate verifies core+ext == core+ext
    from jpype import JClass

    MultiClientEngine = JClass("com.inventzia.pulse.beacon.core.MultiClientEngine")
    Topic = JClass("com.inventzia.pulse.beacon.core.Topic")
    CrossLanguageActor = JClass("com.inventzia.pulse.beacon.core.crosslanguage.CrossLanguageActor")
    CrossLanguageGateway = JClass("com.inventzia.pulse.beacon.core.crosslanguage.CrossLanguageGateway")
    ExtendedBarJ = JClass("com.inventzia.pulse.ext.schemas.ExtendedBar")   # the extension's Java record
    JList = JClass("java.util.List")
    JMap = JClass("java.util.Map")
    JThread = JClass("java.lang.Thread")

    bars = Topic("ext.bars", ExtendedBarJ.class_)
    engine = MultiClientEngine("Engine", start, end)

    py_feed = CrossLanguageGateway("Py-bar-feed", start, end).withTopic(bars)
    py_consumer = CrossLanguageActor("Py-consumer")
    engine.registerPublisher(py_feed, bars, JList.of(SYMB))
    engine.registerActor(py_consumer, JMap.of(bars, JList.of(SYMB)), JMap.of())

    received = []
    consume_streamer = CrossLanguageStreamer(py_consumer, RecordingConsumer("Consumer", received))
    produce_streamer = CrossLanguageStreamer(py_feed, ExtendedBarFeed("Feed", "ext.bars", times))
    errors = {}

    def _guarded(fn, label, **kwargs):
        def wrapper():
            try:
                fn(**kwargs)
            except BaseException as exc:  # noqa: BLE001
                errors.setdefault(label, exc)
                LOG.severe(f"streamer '{label}' failed: {exc!r}")
        return wrapper

    t_consume = threading.Thread(target=_guarded(consume_streamer.run_consume, "consume"),
                                 name="consume", daemon=True)
    t_consume.start()
    engine_thread = JThread(engine, "Engine-thread")
    engine_thread.start()
    _await_started(engine)
    JThread(py_feed, "py-feed").start()
    t_produce = threading.Thread(
        target=_guarded(produce_streamer.run_produce, "produce",
                        on_error=lambda e: errors.setdefault("produce", e)),
        name="produce", daemon=True)
    t_produce.start()

    deadline = time.monotonic() + 20.0
    while engine_thread.isAlive() and time.monotonic() < deadline:
        if errors:
            break
        time.sleep(0.05)
    if errors and engine_thread.isAlive():
        engine_thread.interrupt()
        engine_thread.join(2_000)
    for t in (t_produce, t_consume):
        t.join(5.0)
    if errors:
        label, exc = next(iter(errors.items()))
        raise RuntimeError(f"extended-bar run aborted: streamer '{label}' failed") from exc

    return received, str(engine.status().name()), times


def main(realtime=False):
    """Run the flow and report; returns a process exit code (0 on success).

    Used by the two runnable scripts, ``historical_extended_bar_run.py`` and
    ``realtime_extended_bar_run.py``.
    """
    received, status, times = run(realtime=realtime)
    ok = status == "COMPLETE" and received == expected(times)
    LOG.info(f"mode={'REAL_TIME' if realtime else 'COMPRESSED'} status={status}; received {len(received)} bars")
    for r in received:
        print("  ", r)
    print("EXTENDED BAR RUN OK" if ok else "EXTENDED BAR RUN FAILED")
    return 0 if ok else 1
