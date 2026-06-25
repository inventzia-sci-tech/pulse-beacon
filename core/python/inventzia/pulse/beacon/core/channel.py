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
The outbound bus for a Python component.

The Python mirror of the bus an ``AbstractActor`` is bound to: it carries a
datum the component wants to emit back onto the engine. It wraps the Java
cross-language endpoint and calls its ``publishTagged(topicName, taggedJson)``,
serialising the datum to the self-describing tagged envelope first. The
component never sees the endpoint or any JSON — only :meth:`publish`.
"""

from datum.python.inventzia.pulse.data.datum.codec import to_tagged_json
from datum.python.inventzia.pulse.data.datum.datum import Datum


class BeaconChannel:
    """Publishes a datum onto the bus through the Java cross-language endpoint."""

    def __init__(self, endpoint):
        # endpoint: the Java CrossLanguageActor (a JPype/JEP proxy) exposing
        # publishTagged(str, str). Held opaquely so this class stays JVM-agnostic.
        self._endpoint = endpoint

    def publish(self, topic_name: str, datum: Datum) -> None:
        """Serialise ``datum`` and publish it on ``topic_name``."""
        self._endpoint.publishTagged(topic_name, to_tagged_json(datum))
