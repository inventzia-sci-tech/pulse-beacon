# SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
# Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.

# Cross-language boundary (Python side)

The Python half of running pulse-beacon components across the Python ↔ Java boundary.
`cross_language_streamer.py` is the JVM-agnostic driver (one `run_consume` /
`run_produce` loop against a Java `CrossLanguage*` endpoint); it is reused unchanged by
every option below. The two files beside it are the direction-specific bootstraps.

## Options

**In-process, Python-host (JPype)** — a Python process boots the JVM (`jpype_host.py`).
Rationale: launch/debug from a Python IDE (PyCharm); simplest (pure pip).
Entry points: `../examples/historic_run_jpype.py`, `../examples/realtime_run_jpype.py`.

**In-process, Java-host (JEP)** — the JVM boots CPython; the JVM is the host.
Rationale: launch/debug from a Java IDE, Java-driven runs.
`jep_host.py` here is the generic factory (`run_consume` / `run_produce`, build a
component by name and drive it); the Java `JepLauncher` calls it, one interpreter per
component. Entry point is Java: `examples/HistoricRunJepExample.java`. Native env setup:
the Java package's `JEP_README.md`.

**Out-of-process (ZMQ)** — *planned*. Sockets for distributed/live deployment; same
components, streamer, and wire format.

## See also
- Design: `pulse-beacon/docs/cross-language-python-inprocess.md`
