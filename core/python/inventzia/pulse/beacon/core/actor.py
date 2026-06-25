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
Base class for a consumer/strategy actor implemented in Python.

The Python mirror of the Java ``AbstractActor``: a reactive component that
receives events on the topics it subscribes to and may publish new events back
onto the bus. It is pure Python and JVM-agnostic — it never imports a JVM
bridge. The cross-language streamer drives ``on_startup`` / ``on_event`` /
``on_shutdown`` from the foreign side, and :meth:`publish` goes out through a bound
:class:`~inventzia.pulse.beacon.core.channel.BeaconChannel`.
"""

from abc import ABC, abstractmethod

from core.python.inventzia.pulse.beacon.core.reporter import ComponentReporter
from datum.python.inventzia.pulse.data.datum.datum import Datum


class BeaconActor(ABC):
    """A reactive actor. Override :meth:`on_event` (and optionally the lifecycle hooks)."""

    def __init__(self, name: str):
        self.name = name
        self.log = ComponentReporter(name)
        self._channel = None  # bound at registration; see bind()

    # ------------------------------------------------------------------
    # Binding — the outbound bus, set before the run starts
    # ------------------------------------------------------------------

    def bind(self, channel) -> None:
        """Bind the outbound :class:`BeaconChannel` this actor publishes through."""
        self._channel = channel

    def publish(self, topic_name: str, datum: Datum) -> None:
        """Publish a datum onto the bus via the bound channel."""
        if self._channel is None:
            raise RuntimeError(
                f"actor '{self.name}' published before being bound to a channel")
        self._channel.publish(topic_name, datum)

    # ------------------------------------------------------------------
    # Lifecycle + inbound — override as needed
    # ------------------------------------------------------------------

    def on_startup(self, time_millis: int) -> None:
        """Called once when the run begins, at the start time. Default: no-op."""

    @abstractmethod
    def on_event(self, topic_name: str, datum: Datum) -> None:
        """Handle one event delivered on ``topic_name``."""

    def on_shutdown(self, time_millis: int) -> None:
        """Called once when the run ends. Default: no-op."""
