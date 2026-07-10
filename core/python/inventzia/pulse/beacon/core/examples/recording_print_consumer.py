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
A ``PrintConsumer`` that also records what it receives into a caller-supplied sink.

Parity glue for the Java-host (JEP) example: the JVM cannot read another thread's
interpreter state, so to check that the Python actor saw exactly the expected merge
the host hands in a ``java.util.List`` and this actor appends ``"topic@time"`` to it
across the bridge (a Java call from Python, proxied by JEP). It is an ordinary
component type — the ``jep_host`` factory instantiates it by name like any other,
so nothing example-specific leaks into the generic scaffold. (Under JPype the same
role is an inline subclass in ``historic_run_jpype.py``; here it must be importable
by name, hence a module.)
"""

from core.python.inventzia.pulse.beacon.core.examples.print_consumer import PrintConsumer


class RecordingPrinter(PrintConsumer):
    """Prints each event and appends ``"topic@time"`` to ``received`` for host read-back."""

    def __init__(self, name, received):
        super().__init__(name)
        self._received = received  # a java.util.List handed in by the launcher

    def on_event(self, topic_name, datum):
        self._received.add(f"{topic_name}@{int(datum.datum_time)}")
        super().on_event(topic_name, datum)
