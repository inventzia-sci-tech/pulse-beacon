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
The one control loop that bridges a Python component to its Java counterpart.

This is the single piece of streaming logic, written once and used unchanged in
both embedding directions: under JPype it runs on a Python-owned thread, under
JEP on the thread the JVM created for it. It only *calls* the Java endpoint
(``takeNext`` / ``ackDone`` for a consumer, ``offerNext`` / ``finish`` for a
source) and the Python component (``produce`` / ``on_event`` / lifecycle), so
nothing here is JVM-specific.

Two loop flavours mirror the two roles:

* :meth:`run_consume` — engine → Python. Drains events the engine dispatched,
  routes each to the component, and acks (which releases the engine's dispatch
  thread, the determinism handshake).
* :meth:`run_produce` — Python → engine. Pulls the source's ``produce`` stream
  and offers each event to the Java gateway, which blocks until it has been
  emitted through the time-machine write permit.

A bidirectional gateway runs both loops on two threads — the modern form of the
old ``run_incoming_data`` / ``run_outgoing_data`` pair.
"""

import traceback

from inventzia.pulse.beacon.core.dispatch import dispatch_consume, event_kind
from inventzia.pulse.beacon.core.reporter import ComponentReporter
from inventzia.pulse.data.datum.codec import to_tagged_json

_log = ComponentReporter("streamer")


class CrossLanguageStreamer:
    """Drives one Python component against its Java cross-language endpoint."""

    def __init__(self, endpoint, component, channel=None):
        # endpoint: the Java CrossLanguageActor / CrossLanguageGateway proxy.
        self._endpoint = endpoint
        self._component = component
        # If a channel is supplied and the component can publish, bind it now so
        # on_event handlers may publish during the consume loop.
        if channel is not None and hasattr(component, "bind"):
            component.bind(channel)

    def run_consume(self) -> None:
        """Engine → Python: drain, route, ack — until an END event arrives."""
        while True:
            event = self._endpoint.takeNext()  # blocks
            if event_kind(event) == "END":
                break
            try:
                dispatch_consume(self._component, event)
            except Exception:
                # A failing handler must not kill the streamer: the engine's
                # dispatch thread is blocked waiting for our ack, so dying here
                # would deadlock the run. Log and carry on.
                _log.severe("error handling cross-language event\n" + traceback.format_exc())
            finally:
                self._endpoint.ackDone()  # release the engine's dispatch thread

    def run_produce(self, on_error=None) -> None:
        """Python → engine: offer each produced event; the gateway paces us via the permit.

        ``finish()`` runs in a ``finally`` so that a producer which raises (e.g. a
        source that cannot open its input) or yields nothing still signals completion
        to its Java gateway. Otherwise the engine's all-drivers barrier would wait on a
        dead producer forever — a hang, not a fast failure.

        ``on_error`` (optional) is invoked with the exception *before* the
        ``finally`` runs, so a supervisor can react immediately: if the engine is
        itself wedged (another driver also failed), ``finish()`` may block until the
        engine is torn down, and waiting for it to return would defeat fast failure.
        The exception still propagates after ``finish()`` for callers that rely on it.
        """
        try:
            for topic_name, datum in self._component.produce():
                self._endpoint.offerNext(topic_name, to_tagged_json(datum))  # blocks until accepted
        except BaseException as exc:  # noqa: BLE001 — notify, then let it propagate
            if on_error is not None:
                try:
                    on_error(exc)
                except Exception:
                    pass
            raise
        finally:
            self._endpoint.finish()
