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
Python-host re-creation of the Java ``RealTimeHeartbeatExample`` (real-time).

Three independent Java heartbeat gateways (3 s, 6 s, 10 s), each on its own
topic, drive a near-future 30-second wall-clock window. The engine therefore
runs in REAL_TIME, dispatching from its live queue to a *Python* ``PrintConsumer``
in-process via JPype. A second Python actor (``EchoConsumer``) re-publishes each
beat as a ``TextMessage`` on an ``echo`` topic, which a *Java* ``PrintGateway``
sink consumes — so data crosses the boundary both ways in one run:

    Beater-3s  (heartbeat.3s) ─┐                              ┌▶ Py PrintConsumer
    Beater-6s  (heartbeat.6s) ─┼─▶ [ Java engine / live queue ]┤
    Beater-10s (heartbeat.10s)─┘                              └▶ Py EchoConsumer ─▶ (echo) ─▶ Java PrintGateway

Watching the console shows the three cadences interleaving in real time and
coinciding at their common multiples (all three align every 30 s, the 3 s and 6 s
every 6 s, and so on) — exactly as the all-Java example, but with the consumers
written in Python and reached across the cross-language boundary, and the echo
flowing Python → Java back to a Java sink.

Unlike the historical run this is wall-clock paced (it takes about as long as its
window, ~30 s) and is a live demo, not a deterministic replay, so it is not
asserted for exact parity — only that the engine reaches COMPLETE and the Python
consumer saw beats.

Run (from New/, in the shared `pulse` env — its bundled JDK sets JAVA_HOME):
    export PYTHONPATH="pulse-beacon:pulse-data"
    conda run -n pulse python pulse-beacon/core/python/inventzia/pulse/beacon/core/examples/realtime_run_jpype.py
"""

import threading
import time

from core.python.inventzia.pulse.beacon.core.channel import BeaconChannel
from core.python.inventzia.pulse.beacon.core.crosslanguage import jpype_host
from core.python.inventzia.pulse.beacon.core.crosslanguage.cross_language_streamer import CrossLanguageStreamer
from core.python.inventzia.pulse.beacon.core.examples.echo_consumer import EchoConsumer
from core.python.inventzia.pulse.beacon.core.examples.print_consumer import PrintConsumer
from core.python.inventzia.pulse.beacon.core.reporter import ComponentReporter, ReportLevel, configure_logging

LOG = ComponentReporter("realtime-run-jpype")

WINDOW_MS = 30_000   # 30-second run, mirroring the Java example.
# Start ahead of "now" so the engine initialises before the window opens (-> REAL_TIME).
# The buffer must exceed cold-start cost (class loading + first Logback init); too small
# and "now" overshoots start, landing the engine in the (unimplemented) MIXED mode.
LEAD_MS = 8_000


def _await_status(engine, target_name: str, timeout_s: float) -> None:
    """Poll until the engine reaches the named status, or time out."""
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        if str(engine.status().name()) == target_name:
            return
        time.sleep(0.02)
    raise TimeoutError(f"engine did not reach {target_name}; was {engine.status().name()}")


def run() -> tuple[int, str]:
    """Run the real-time heartbeat demo; return ``(beats_seen, status)``.

    ``beats_seen`` is how many events the Python printer received; ``status`` is
    the engine's final status name. Idempotent w.r.t. the JVM.
    """
    configure_logging(ReportLevel.INFO)
    jpype_host.start_jvm()

    from jpype import JClass

    # --- Java classes ---------------------------------------------------
    MultiClientEngine = JClass("com.inventzia.pulse.beacon.core.MultiClientEngine")
    Topic = JClass("com.inventzia.pulse.beacon.core.Topic")
    HeartBeatGateway = JClass("com.inventzia.pulse.beacon.core.gateway.periodic.HeartBeatGateway")
    PrintGateway = JClass("com.inventzia.pulse.beacon.core.examples.PrintGateway")
    CrossLanguageActor = JClass("com.inventzia.pulse.beacon.core.crosslanguage.CrossLanguageActor")
    HeartBeatJ = JClass("com.inventzia.pulse.data.schemas.platform.HeartBeat")
    TextMessageJ = JClass("com.inventzia.pulse.data.schemas.platform.TextMessage")
    JList = JClass("java.util.List")
    JMap = JClass("java.util.Map")
    JThread = JClass("java.lang.Thread")
    JSystem = JClass("java.lang.System")

    now = int(JSystem.currentTimeMillis())
    start = now + LEAD_MS
    end = start + WINDOW_MS

    # --- topics: one per cadence, plus the echo topic -------------------
    fast = Topic("heartbeat.3s", HeartBeatJ.class_)
    medium = Topic("heartbeat.6s", HeartBeatJ.class_)
    slow = Topic("heartbeat.10s", HeartBeatJ.class_)
    echo = Topic("echo", TextMessageJ.class_)

    # --- engine ---------------------------------------------------------
    engine = MultiClientEngine("Engine", start, end)

    # --- Java sources (clock-driving, paced to wall clock) --------------
    beater3 = HeartBeatGateway("Beater-3s", fast, "3S", 3_000, start, start, end)
    beater6 = HeartBeatGateway("Beater-6s", medium, "6S", 6_000, start, start, end)
    beater10 = HeartBeatGateway("Beater-10s", slow, "10S", 10_000, start, start, end)

    # --- Java sink: receives the echo published back by the Python actor -
    echo_sink = PrintGateway("Echo-sink", start, end)

    # --- Java halves of the Python actors -------------------------------
    py_print = CrossLanguageActor("Py-print")
    py_echo = CrossLanguageActor("Py-echo").withTopic(echo)

    # --- registration (mirrors RealTimeHeartbeatExample + echo round-trip)
    engine.registerPublisher(beater3, fast, JList.of("3S"))
    engine.registerPublisher(beater6, medium, JList.of("6S"))
    engine.registerPublisher(beater10, slow, JList.of("10S"))
    engine.registerActor(py_print,
                         JMap.of(fast, JList.of("3S"),
                                 medium, JList.of("6S"),
                                 slow, JList.of("10S")),
                         JMap.of())
    engine.registerActor(py_echo,
                         JMap.of(fast, JList.of("3S"),
                                 medium, JList.of("6S"),
                                 slow, JList.of("10S")),
                         JMap.of(echo, JList.of("ECHO")))
    engine.registerSubscriber(echo_sink, echo, JList.of("ECHO"))

    # --- Python components ----------------------------------------------
    count = [0]

    class CountingPrinter(PrintConsumer):
        def on_event(self, topic_name, datum):
            count[0] += 1
            super().on_event(topic_name, datum)

    printer = CountingPrinter("Print-actor")
    echoer = EchoConsumer("Echo-actor", "echo", "ECHO")

    print_streamer = CrossLanguageStreamer(py_print, printer)
    echo_streamer = CrossLanguageStreamer(py_echo, echoer, BeaconChannel(py_echo))

    # --- run: consumer streamers up first, then engine, then sources ----
    t_print = threading.Thread(target=print_streamer.run_consume, name="py-print-streamer", daemon=True)
    t_echo = threading.Thread(target=echo_streamer.run_consume, name="py-echo-streamer", daemon=True)
    t_print.start()
    t_echo.start()

    engine_thread = JThread(engine, "Engine-thread")
    engine_thread.start()
    _await_status(engine, "STARTED", 5.0)

    JThread(beater3, "beater-3s").start()
    JThread(beater6, "beater-6s").start()
    JThread(beater10, "beater-10s").start()
    JThread(echo_sink, "echo-sink").start()

    engine_thread.join(LEAD_MS + WINDOW_MS + 10_000)
    for t in (t_print, t_echo):
        t.join(5.0)

    status = str(engine.status().name())
    LOG.info(f"finished — engine status: {status}; printer received {count[0]} beats")
    return count[0], status


def main() -> int:
    beats, status = run()
    ok = status == "COMPLETE" and beats > 0
    if not ok:
        LOG.severe(f"run did not complete cleanly: status={status}, beats={beats}")
    print("REALTIME OK" if ok else "REALTIME FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    import sys
    sys.exit(main())
