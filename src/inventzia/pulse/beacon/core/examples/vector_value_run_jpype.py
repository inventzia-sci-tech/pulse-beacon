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
Python-host example: a generic ``VectorValue`` indicator stream over the JPype bridge.

A Python source gateway emits a short stream of MACD observations — a labelled
*decimal vector* (`[macd, signal, hist]`) per timestamp; the Java engine routes
them by datum key/time; a Python consumer receives them and checks the decimals
**and** the labels survived the cross-language boundary intact. This exercises the
generic ``common.VectorValue`` datum end-to-end through the real engine and codec
(``List<BigDecimal>`` <-> ``list[Decimal]`` as strings; ``valueIds`` parallel array).

Run (in the shared `pulse` env, after ``pip install -e ./pulse-data -e ./pulse-beacon``):
    conda run -n pulse python -m inventzia.pulse.beacon.core.examples.vector_value_run_jpype
"""

import threading
import time
from decimal import Decimal

from inventzia.pulse.beacon.core.actor import BeaconActor
from inventzia.pulse.beacon.core.crosslanguage import jpype_host
from inventzia.pulse.beacon.core.crosslanguage.cross_language_streamer import CrossLanguageStreamer
from inventzia.pulse.beacon.core.gateway import BeaconGateway
from inventzia.pulse.beacon.core.reporter import ComponentReporter, ReportLevel, configure_logging
from inventzia.pulse.data.schemas.common.vector_value import VectorValue

LOG = ComponentReporter("vector-value-run")

START = 1_283_630_000_000
END   = 1_283_630_003_000
KEY   = "AAPL.MACD"
LABELS = ["macd", "signal", "hist"]

# The MACD vector the feed emits at each timestamp.
SERIES = [
    (START + 0,    [Decimal("1.20"), Decimal("0.80"), Decimal("0.40")]),
    (START + 1000, [Decimal("1.35"), Decimal("0.90"), Decimal("0.45")]),
    (START + 2000, [Decimal("1.10"), Decimal("1.00"), Decimal("0.10")]),
]
EXPECTED = [(KEY, t, vals, LABELS) for t, vals in SERIES]


class VectorValueFeed(BeaconGateway):
    """A source gateway emitting a fixed stream of labelled VectorValue observations."""

    def __init__(self, name: str, topic_name: str):
        super().__init__(name)
        self._topic_name = topic_name

    def produce(self):
        for t, vals in SERIES:
            yield self._topic_name, VectorValue(key=KEY, time=t, values=vals, valueIds=LABELS)


class RecordingConsumer(BeaconActor):
    """Records each received VectorValue's key/time/values/valueIds."""

    def __init__(self, name: str, sink: list):
        super().__init__(name)
        self._sink = sink

    def on_event(self, topic_name, datum) -> None:
        self._sink.append((str(datum.datum_key), int(datum.datum_time),
                           list(datum.values), list(datum.value_ids) if datum.value_ids else None))
        LOG.info(f"recv {topic_name} {datum.datum_key}@{datum.datum_time} "
                 f"values={datum.values} ids={datum.value_ids}")


def _await_started(engine, timeout_s: float = 5.0) -> None:
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        if str(engine.status().name()) == "STARTED":
            return
        time.sleep(0.02)
    raise TimeoutError("engine did not reach STARTED")


def run() -> tuple[list, str]:
    """Build the topology, run to completion, return ``(received, status)``."""
    configure_logging(ReportLevel.INFO)
    jpype_host.start_jvm()
    from jpype import JClass

    MultiClientEngine = JClass("com.inventzia.pulse.beacon.core.MultiClientEngine")
    Topic = JClass("com.inventzia.pulse.beacon.core.Topic")
    CrossLanguageActor = JClass("com.inventzia.pulse.beacon.core.crosslanguage.CrossLanguageActor")
    CrossLanguageGateway = JClass("com.inventzia.pulse.beacon.core.crosslanguage.CrossLanguageGateway")
    VectorValueJ = JClass("com.inventzia.pulse.data.schemas.common.VectorValue")
    JList = JClass("java.util.List")
    JMap = JClass("java.util.Map")
    JThread = JClass("java.lang.Thread")

    indicators = Topic("indicators", VectorValueJ.class_)
    engine = MultiClientEngine("Engine", START, END)

    py_feed_gw = CrossLanguageGateway("Py-vec-feed", START, END).withTopic(indicators)
    py_consumer = CrossLanguageActor("Py-consumer")
    engine.registerPublisher(py_feed_gw, indicators, JList.of(KEY))
    engine.registerActor(py_consumer, JMap.of(indicators, JList.of(KEY)), JMap.of())

    received: list = []
    consumer = RecordingConsumer("Consumer-actor", received)
    feed = VectorValueFeed("Feed", "indicators")
    consume_streamer = CrossLanguageStreamer(py_consumer, consumer)
    produce_streamer = CrossLanguageStreamer(py_feed_gw, feed)

    errors: dict = {}

    def _guarded(fn, label, **kwargs):
        def wrapper():
            try:
                fn(**kwargs)
            except BaseException as exc:  # noqa: BLE001
                errors.setdefault(label, exc)
                LOG.severe(f"streamer '{label}' failed: {exc!r}")
        return wrapper

    t_consume = threading.Thread(target=_guarded(consume_streamer.run_consume, "consume"),
                                 name="consume-streamer", daemon=True)
    t_consume.start()

    engine_thread = JThread(engine, "Engine-thread")
    engine_thread.start()
    _await_started(engine)

    JThread(py_feed_gw, "py-feed").start()
    t_produce = threading.Thread(
        target=_guarded(produce_streamer.run_produce, "produce",
                        on_error=lambda e: errors.setdefault("produce", e)),
        name="produce-streamer", daemon=True)
    t_produce.start()

    deadline = time.monotonic() + 15.0
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
        raise RuntimeError(f"vector-value run aborted: streamer '{label}' failed") from exc

    return received, str(engine.status().name())


def main() -> int:
    received, status = run()
    ok = status == "COMPLETE" and received == EXPECTED
    LOG.info(f"engine status: {status}; consumer received {len(received)} vectors")
    for r in received:
        print("  ", r)
    print("VECTOR VALUE RUN OK" if ok else "VECTOR VALUE RUN FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    import sys
    sys.exit(main())
