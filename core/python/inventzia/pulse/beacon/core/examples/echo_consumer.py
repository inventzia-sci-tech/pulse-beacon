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
Python port of the Java ``EchoConsumer`` example.

A publishing actor: for every event it receives, it emits a summary
``TextMessage`` on its own output topic. Demonstrates the outbound side of a
Python actor — :meth:`~inventzia.pulse.beacon.core.actor.BeaconActor.publish`
goes out through the bound channel and back onto the bus. The actor must be
registered as a publisher of its echo topic for the echo to be routed.
"""

from core.python.inventzia.pulse.beacon.core.actor import BeaconActor
from datum.python.inventzia.pulse.data.datum.datum import Datum
from schemas.schemas_py.inventzia.pulse.data.schemas.platform.text_message import TextMessage


class EchoConsumer(BeaconActor):
    """Re-emits every received event as a summary TextMessage on its echo topic."""

    def __init__(self, name: str, echo_topic_name: str, echo_key: str):
        super().__init__(name)
        self._echo_topic_name = echo_topic_name
        self._echo_key = echo_key

    def on_event(self, topic_name: str, datum: Datum) -> None:
        # Re-stamp the echo at the source event's time, carrying a text summary.
        summary = f"{topic_name} :: {datum!r}"
        echo = TextMessage(msgKey=self._echo_key, msgTime=datum.datum_time, text=summary)
        self.log.large_info(lambda: f"echo {topic_name} -> {self._echo_topic_name}")
        self.publish(self._echo_topic_name, echo)
