# SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
# Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
#
# This file is part of pulse-beacon.
#
# pulse-beacon is dual-licensed:
#   - Under the GNU Affero General Public License v3.0 or later (see LICENSE-AGPL-3.0).
#   - Under a commercial license (see LICENSE-COMMERCIAL.txt).
#     Contact operations@inventzia.com.
"""
Python-host re-creation of the Java ``HistoricRunExample`` (compressed-time).

Same topology, but the Printer and Echo consumers are Python actors and one
source (``stream.one``) is a Python feed gateway, all running in-process with
the Java engine via JPype:

    py-feed (stream.one) ─┐
    reader-2 (stream.two)─┼─▶ [ Java engine / time machine ] ─▶ Py PrintConsumer
    beater   (heartbeat) ─┘                                  └▶ Py EchoConsumer ─▶ (echo) ─▶ PrintGateway

The Java engine still owns the clock and routing; the Python components run on
streamer threads and cross the boundary as self-describing tagged JSON. The run
asserts that the Python printer received exactly the merged, event-time-ordered
sequence the all-Java run produces.

Run (in the shared `pulse` env — its bundled JDK sets JAVA_HOME — after
``pip install -e ./pulse-data -e ./pulse-beacon``):
    conda run -n pulse python -m inventzia.pulse.beacon.core.examples.historic_run_jpype

The parity check is also exercised as a test (``run()`` returns the recorded
events and final engine status): see ``tests/test_historic_run_jpype.py``.
"""

import threading
import time
from pathlib import Path

from inventzia.pulse.beacon.core.channel import BeaconChannel
from inventzia.pulse.beacon.core.crosslanguage import jpype_host
from inventzia.pulse.beacon.core.crosslanguage.cross_language_streamer import CrossLanguageStreamer
from inventzia.pulse.beacon.core.examples.echo_consumer import EchoConsumer
from inventzia.pulse.beacon.core.examples.message_feed_gateway import MessageFeedGateway
from inventzia.pulse.beacon.core.examples.print_consumer import PrintConsumer
from inventzia.pulse.beacon.core.reporter import ComponentReporter, ReportLevel, configure_logging

LOG = ComponentReporter("historic-run-jpype")

START = 1_283_630_000_000
END = 1_283_630_007_000

EXPECTED = [
    ("stream.one", 1_283_630_000_000),
    ("stream.two", 1_283_630_001_000),
    ("heartbeat",  1_283_630_001_500),
    ("stream.one", 1_283_630_002_000),
    ("stream.two", 1_283_630_003_000),
    ("heartbeat",  1_283_630_003_500),
    ("stream.one", 1_283_630_004_000),
    ("stream.two", 1_283_630_005_000),
    ("heartbeat",  1_283_630_005_500),
    ("stream.one", 1_283_630_006_000),
]


def _data_dir() -> Path:
    """Directory of the example JSONL fixtures.

    Prefers the fixtures shipped as package data (``examples/_data``), located via
    ``importlib.resources`` so the example runs from an installed wheel with no
    source tree. Falls back to the Java example resources for a bare checkout that
    somehow lacks the packaged copy.
    """
    try:
        from importlib.resources import files
        res = Path(str(files("inventzia.pulse.beacon.core.examples").joinpath("_data")))
        if res.is_dir():
            return res
    except (ModuleNotFoundError, FileNotFoundError, TypeError):
        pass
    # Source-checkout fallback: the Java example resources.
    # .../pulse-beacon/src/inventzia/pulse/beacon/core/examples/historic_run_jpype.py
    beacon_root = Path(__file__).resolve().parents[6]
    return beacon_root / "core" / "java" / "src" / "main" / "resources" / "examples" / "data"


def _await_status(engine, target_name: str, timeout_s: float) -> None:
    """Poll until the engine reaches >= the named status, or time out."""
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        if str(engine.status().name()) == target_name:
            return
        time.sleep(0.02)
    raise TimeoutError(f"engine did not reach {target_name}; was {engine.status().name()}")


def run() -> tuple[list[tuple[str, int]], str]:
    """Build the topology, run to completion, and return ``(received, status)``.

    ``received`` is the ``(topic_name, datum_time)`` sequence the Python printer
    saw; ``status`` is the engine's final status name. Importable so a test can
    assert parity (``received == EXPECTED`` and ``status == "COMPLETE"``) without
    the CLI wrapper. Idempotent w.r.t. the JVM (``start_jvm`` reuses a running one).
    """
    configure_logging(ReportLevel.INFO)
    jpype_host.start_jvm()

    from jpype import JClass

    # --- Java classes ---------------------------------------------------
    MultiClientEngine = JClass("com.inventzia.pulse.beacon.core.MultiClientEngine")
    Topic = JClass("com.inventzia.pulse.beacon.core.Topic")
    JsonlReaderGateway = JClass("com.inventzia.pulse.beacon.core.gateway.file.JsonlReaderGateway")
    HeartBeatGateway = JClass("com.inventzia.pulse.beacon.core.gateway.periodic.HeartBeatGateway")
    PrintGateway = JClass("com.inventzia.pulse.beacon.core.examples.PrintGateway")
    CrossLanguageActor = JClass("com.inventzia.pulse.beacon.core.crosslanguage.CrossLanguageActor")
    CrossLanguageGateway = JClass("com.inventzia.pulse.beacon.core.crosslanguage.CrossLanguageGateway")
    TextMessageJ = JClass("com.inventzia.pulse.data.schemas.platform.TextMessage")
    HeartBeatJ = JClass("com.inventzia.pulse.data.schemas.platform.HeartBeat")
    JList = JClass("java.util.List")
    JMap = JClass("java.util.Map")
    JThread = JClass("java.lang.Thread")
    Paths = JClass("java.nio.file.Paths")

    data = _data_dir()

    # --- topics ---------------------------------------------------------
    stream_one = Topic("stream.one", TextMessageJ.class_)
    stream_two = Topic("stream.two", TextMessageJ.class_)
    heartbeat = Topic("heartbeat", HeartBeatJ.class_)
    echo = Topic("echo", TextMessageJ.class_)

    # --- engine ---------------------------------------------------------
    engine = MultiClientEngine("Engine", START, END)

    # --- Java sources (clock-driving) -----------------------------------
    reader_two = JsonlReaderGateway("Reader-2", stream_two, JList.of("A"),
                                    Paths.get(str(data / "messages_two.jsonl")), START, END)
    beater = HeartBeatGateway("Beater", heartbeat, "BEAT", 2_000, START + 1_500, START, END)
    echo_sink = PrintGateway("Echo-sink", START, END)

    # --- Java halves of the Python components ---------------------------
    py_feed_gw = CrossLanguageGateway("Py-feed", START, END).withTopic(stream_one)
    py_print = CrossLanguageActor("Py-print")
    py_echo = CrossLanguageActor("Py-echo").withTopic(echo)

    # --- registration (mirrors HistoricRunExample) ----------------------
    engine.registerPublisher(py_feed_gw, stream_one, JList.of("A"))
    engine.registerPublisher(reader_two, stream_two, JList.of("A"))
    engine.registerPublisher(beater, heartbeat, JList.of("BEAT"))
    engine.registerActor(py_print,
                         JMap.of(stream_one, JList.of("A"),
                                 stream_two, JList.of("A"),
                                 heartbeat, JList.of("BEAT")),
                         JMap.of())
    engine.registerActor(py_echo,
                         JMap.of(stream_one, JList.of("A")),
                         JMap.of(echo, JList.of("ECHO")))
    engine.registerSubscriber(echo_sink, echo, JList.of("ECHO"))

    # --- Python components ----------------------------------------------
    received: list[tuple[str, int]] = []

    class RecordingPrinter(PrintConsumer):
        def on_event(self, topic_name, datum):
            received.append((str(topic_name), int(datum.datum_time)))
            super().on_event(topic_name, datum)

    printer = RecordingPrinter("Print-actor")
    echoer = EchoConsumer("Echo-actor", "echo", "ECHO")
    feed = MessageFeedGateway("Feed", "stream.one", data / "messages_one.jsonl")

    print_streamer = CrossLanguageStreamer(py_print, printer)
    echo_streamer = CrossLanguageStreamer(py_echo, echoer, BeaconChannel(py_echo))
    feed_streamer = CrossLanguageStreamer(py_feed_gw, feed)

    # Capture streamer-thread failures so a dead producer/consumer surfaces as a fast,
    # explicit error rather than a silent parity mismatch or a full-timeout hang.
    errors: dict[str, BaseException] = {}

    def _record(label, exc):
        errors.setdefault(label, exc)
        LOG.severe(f"streamer '{label}' failed: {exc!r}")

    def _guarded(fn, label, **kwargs):
        def wrapper():
            try:
                fn(**kwargs)
            except BaseException as exc:  # noqa: BLE001 — report any streamer failure
                _record(label, exc)
        return wrapper

    # --- run: consumer streamers up first, then engine, then sources ----
    t_print = threading.Thread(target=_guarded(print_streamer.run_consume, "py-print"),
                               name="py-print-streamer", daemon=True)
    t_echo = threading.Thread(target=_guarded(echo_streamer.run_consume, "py-echo"),
                              name="py-echo-streamer", daemon=True)
    t_print.start()
    t_echo.start()

    engine_thread = JThread(engine, "Engine-thread")
    engine_thread.start()
    _await_status(engine, "STARTED", 5.0)

    JThread(reader_two, "reader-2").start()
    JThread(beater, "beater").start()
    JThread(echo_sink, "echo-sink").start()
    JThread(py_feed_gw, "py-feed").start()
    t_feed = threading.Thread(
        target=_guarded(feed_streamer.run_produce, "py-feed",
                        on_error=lambda e: _record("py-feed", e)),  # signal before finish() blocks
        name="py-feed-streamer", daemon=True)
    t_feed.start()

    # Wait for the engine to finish, but bail out the moment a streamer dies. A failed
    # producer's finally-block finish() lets the engine drain, so this returns promptly
    # instead of blocking the whole timeout on a barrier that will never be satisfied.
    deadline = time.monotonic() + 15.0
    while engine_thread.isAlive() and time.monotonic() < deadline:
        if errors:
            break
        time.sleep(0.05)

    # A streamer died (or we hit the deadline): tear the engine down *before* joining,
    # so the streamer threads still blocked in finish()/takeNext() unblock instead of
    # burning their full join timeout each.
    if errors and engine_thread.isAlive():
        engine_thread.interrupt()
        engine_thread.join(2_000)

    for t in (t_feed, t_print, t_echo):
        t.join(5.0)

    if errors:
        label, exc = next(iter(errors.items()))
        raise RuntimeError(f"cross-language run aborted: streamer '{label}' failed") from exc

    status = str(engine.status().name())
    LOG.info(f"engine status: {status}; printer received {len(received)} events")
    return received, status


def main() -> int:
    received, status = run()

    # --- parity assertions ----------------------------------------------
    ok = True
    if received != EXPECTED:
        ok = False
        LOG.severe(f"PARITY MISMATCH\n  expected: {EXPECTED}\n  received: {received}")
    if status != "COMPLETE":
        ok = False
        LOG.severe(f"engine did not COMPLETE: {status}")

    print("PARITY OK" if ok else "PARITY FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    import sys
    sys.exit(main())
