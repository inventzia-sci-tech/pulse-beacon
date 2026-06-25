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
Base class for a gateway (external boundary) implemented in Python.

The Python mirror of the Java ``AbstractGateway`` boundary role. A gateway can
be a **source** (override :meth:`produce` to yield events in event-time order,
injecting external data into the run as a clock driver) and/or a **sink**
(override :meth:`on_event` to forward run events out to an external system) —
the same dual role as the old ``PythonGateway``.

Like :class:`~inventzia.pulse.beacon.core.actor.BeaconActor`, it is pure Python:
the cross-language streamer drives the source via :meth:`produce` and the sink
via :meth:`on_event`, and the determinism handshake (write-permit pacing on the
source, ack on the sink) lives in the Java half.
"""

from collections.abc import Iterator

from core.python.inventzia.pulse.beacon.core.reporter import ComponentReporter
from datum.python.inventzia.pulse.data.datum.datum import Datum


class BeaconGateway:
    """An external boundary. Override :meth:`produce` (source) and/or :meth:`on_event` (sink)."""

    def __init__(self, name: str):
        self.name = name
        self.log = ComponentReporter(name)

    # ------------------------------------------------------------------
    # Source role — yield events in event-time order; default: not a source
    # ------------------------------------------------------------------

    def produce(self) -> Iterator[tuple[str, Datum]]:
        """Yield ``(topic_name, datum)`` pairs in event-time order. Default: empty."""
        return iter(())

    # ------------------------------------------------------------------
    # Sink role — receive run events; default: not a sink
    # ------------------------------------------------------------------

    def on_event(self, topic_name: str, datum: Datum) -> None:
        """Handle one event delivered on ``topic_name``. Default: no-op."""

    # ------------------------------------------------------------------
    # Lifecycle hooks — override as needed
    # ------------------------------------------------------------------

    def on_startup(self, time_millis: int) -> None:
        """Called once when the run begins. Default: no-op."""

    def on_shutdown(self, time_millis: int) -> None:
        """Called once when the run ends. Default: no-op."""
