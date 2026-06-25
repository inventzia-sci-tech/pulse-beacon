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
A Python source gateway: an external feed that injects ``TextMessage`` events
into a run, in event-time order, as a clock driver.

The Python counterpart of the Java ``JsonlReaderGateway`` for the message
streams: it reads a JSONL file of ``TextMessage`` records and yields them from
:meth:`produce`. Used to prove the gateway/source role end-to-end — a Python
feed driving the engine's clock alongside (or in place of) a Java reader.
"""

from collections.abc import Iterator
from pathlib import Path

from core.python.inventzia.pulse.beacon.core.gateway import BeaconGateway
from datum.python.inventzia.pulse.data.datum.codec import from_json
from schemas.schemas_py.inventzia.pulse.data.schemas.platform.text_message import TextMessage


class MessageFeedGateway(BeaconGateway):
    """Reads a JSONL file of TextMessages and produces them on a topic, in file order."""

    def __init__(self, name: str, topic_name: str, path: str | Path):
        super().__init__(name)
        self._topic_name = topic_name
        self._path = Path(path)

    def produce(self) -> Iterator[tuple[str, TextMessage]]:
        with self._path.open(encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if not line:
                    continue
                datum = from_json(line, TextMessage)
                self.log.large_info(lambda: f"feed {self._topic_name} @ {datum.datum_time}")
                yield self._topic_name, datum
