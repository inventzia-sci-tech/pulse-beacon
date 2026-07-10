<!--
SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
-->

# Cross-language boundary (Java side)

The Java half of running pulse-beacon components across the Python ↔ Java boundary.
The boundary types are shared by every option below: `CrossLanguageActor` /
`CrossLanguageGateway` (the Java stand-ins the engine talks to) and
`CrossLanguageEvent`; the wire format is pulse-data's self-describing tagged JSON.
The Python streamer and components are the same regardless of option — only *who
boots whom* differs.

## Options

**In-process, Python-host (JPype)** — a Python process boots the JVM.
Rationale: launch/debug from a Python IDE (PyCharm); simplest to set up (pure pip).
Driven from Python — no Java entry point here.
Entry point: `core/python/.../examples/historic_run_jpype.py` (`jpype_host.py` boots the JVM).

**In-process, Java-host (JEP)** — the JVM boots CPython.
Rationale: launch/debug from a Java IDE; keep the run Java-driven.
Entry points: `examples/HistoricRunJepExample.java` (compressed-time parity) and
`examples/RealTimeRunJepExample.java` (live heartbeat + echo); infra: `JepLauncher.java`
(this package) + the Python factory `crosslanguage/jep_host.py`. Native env setup:
**`JEP_README.md`**; run either with `../run-jep-example.sh [ExampleName]`.

**Out-of-process (ZMQ)** — *planned*. Sockets instead of in-process, for distributed
or live deployment and process isolation; same programming model and wire format.

## See also
- Design: `docs/cross-language-python-inprocess.md`
- JEP native setup (Windows + Linux): `JEP_README.md`
