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
Routing of one inbound cross-language event to a component's callbacks.

A ``CrossLanguageEvent`` crossing the boundary carries a transport-level
``kind`` (``DATA`` / ``START`` / ``STOP`` / ``END``). :func:`dispatch_consume`
maps a single event to the right component callback, decoding the
self-describing tagged JSON to a native pulse-data model for ``DATA`` events so
the component only ever sees a typed datum, never JSON. ``END`` is the
streamer loop's concern, not this function's.
"""

from datum.python.inventzia.pulse.data.datum.codec import from_tagged_json


def event_kind(event) -> str:
    """The kind name of a cross-language event as a plain string (e.g. ``"DATA"``)."""
    # event.kind() is a Java CrossLanguageEvent.Kind enum constant under the
    # in-process bridge; .name() yields its string name.
    return event.kind().name()


def dispatch_consume(component, event) -> None:
    """Route one inbound event to ``component``'s lifecycle / on_event callbacks."""
    kind = event_kind(event)
    if kind == "DATA":
        datum = from_tagged_json(event.taggedJson())
        component.on_event(event.topicName(), datum)
    elif kind == "START":
        component.on_startup(event.time())
    elif kind == "STOP":
        component.on_shutdown(event.time())
    # END: handled by the streamer loop (terminates it); nothing to dispatch.
