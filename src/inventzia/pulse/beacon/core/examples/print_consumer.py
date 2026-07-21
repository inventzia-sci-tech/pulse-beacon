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
Python port of the Java ``PrintConsumer`` example.

The simplest consuming actor: it prints each event it receives and logs its own
start-up and shut-down. Pure Python, JVM-agnostic — the same class runs under
either embedding direction.
"""

from inventzia.pulse.beacon.core.actor import BeaconActor
from inventzia.pulse.data.datum.datum import Datum


class PrintConsumer(BeaconActor):
    """Prints each event and counts them."""

    def __init__(self, name: str):
        super().__init__(name)
        self._count = 0

    def on_startup(self, time_millis: int) -> None:
        self.log.info(f"starting up @ {time_millis}")

    def on_event(self, topic_name: str, datum: Datum) -> None:
        self._count += 1
        self.log.info(f"#{self._count} {topic_name}: {datum!r}")

    def on_shutdown(self, time_millis: int) -> None:
        self.log.info(f"shutting down @ {time_millis} (received {self._count} events)")
