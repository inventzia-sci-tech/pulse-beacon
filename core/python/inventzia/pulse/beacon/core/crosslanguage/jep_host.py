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
Java-host (JEP) scaffold: a generic factory that runs a Python component's streamer.

The mirror of :mod:`jpype_host`, for the opposite embedding direction. Under JPype
a Python process boots the JVM; under JEP the **JVM is the host** and creates a
CPython interpreter (one per streamer thread) via JEP. The Java launcher drives
everything declaratively: it hands in the Java ``CrossLanguage*`` endpoint, the
*fully-qualified name* of the Python component type, and its constructor args, and
picks which streamer loop to run.

This module is a **factory, not an example** — it knows no component names and
imports nothing from the ``examples`` package. The two entry points are named
after the two streamer loops, which is what they actually run and is orthogonal to
component type:

* :func:`run_consume` — engine → Python. Used for an **Actor** (which consumes via
  ``on_event`` and may publish back) and for a **Gateway** in its **sink** role.
* :func:`run_produce` — Python → engine. Used for a **Gateway** in its **source**
  role (which yields from ``produce``).

The component itself (any ``BeaconActor`` / ``BeaconGateway``, or a purpose-built
subclass such as a recording actor for parity) is constructed by fully-qualified
name — the example-specific choice of *which* types with *which* args lives on the
Java side, not here.

Everything below the factory — the components, ``dispatch``, and the streamer — is
JVM-agnostic and identical to the JPype path; only *who starts whom* differs. Each
``run_*`` blocks on the streamer loop until it ends (``END`` for consume, source
exhaustion for produce), so the calling Java thread *is* the streamer thread —
which is what JEP requires, since an interpreter is bound to its creating thread.
"""

import importlib

from core.python.inventzia.pulse.beacon.core.channel import BeaconChannel
from core.python.inventzia.pulse.beacon.core.crosslanguage.cross_language_streamer import CrossLanguageStreamer


def _instantiate(type_fqn: str, args):
    """Import ``pkg.module.ClassName`` and construct it with ``args``.

    ``args`` is whatever the Java launcher handed in (typically a ``java.util.List``
    of constructor arguments); it is materialised to a Python list so both plain
    values and Java-object arguments (e.g. a parity sink) pass straight through.
    """
    module_path, _, class_name = type_fqn.rpartition(".")
    if not module_path:
        raise ValueError(
            f"component type must be a fully-qualified 'module.Class' name, got {type_fqn!r}")
    cls = getattr(importlib.import_module(module_path), class_name)
    return cls(*list(args))


def run_consume(endpoint, type_fqn, args, publishes=False):
    """Build ``type_fqn(*args)`` and drive its consume loop (engine → Python) until END.

    :param endpoint: the Java ``CrossLanguageActor`` / sink ``CrossLanguageGateway`` proxy.
    :param type_fqn: fully-qualified name of the Python Actor / sink-Gateway class.
    :param args: constructor arguments (a Java list; Java objects pass through).
    :param publishes: if true, bind a :class:`BeaconChannel` so the component can
        publish back onto the bus (an Actor with a ``Pub`` role, e.g. an echo actor).
    """
    component = _instantiate(type_fqn, args)
    channel = BeaconChannel(endpoint) if publishes else None
    CrossLanguageStreamer(endpoint, component, channel).run_consume()


def run_produce(endpoint, type_fqn, args):
    """Build ``type_fqn(*args)`` and drive its produce loop (Python → engine) until exhausted.

    :param endpoint: the Java source ``CrossLanguageGateway`` proxy.
    :param type_fqn: fully-qualified name of the Python source-Gateway class.
    :param args: constructor arguments (a Java list; Java objects pass through).
    """
    component = _instantiate(type_fqn, args)
    CrossLanguageStreamer(endpoint, component).run_produce()
