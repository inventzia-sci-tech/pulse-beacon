# SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
# Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
#
# This file is part of pulse-beacon.
#
# pulse-beacon is dual-licensed:
#   - Under the GNU Affero General Public License v3.0 or later (see LICENSE-AGPL-3.0).
#   - Under a commercial license (see LICENSE-COMMERCIAL.txt).
#     Contact operations@inventzia.com.
"""Launcher for the real-time echo example — a long run, recorded, meant to be watched.

The run itself is the Java ``RealTimeEchoExample``, bundled in the wheel's runtime jar. This module
only boots the JVM and hands over: the example writes its own recording through the launcher-side
``run`` package, and re-creating that in Python would duplicate the very code the recording is meant
to exercise. What it produces:

* a **5-second** heartbeat and an **18-second** heartbeat, which drift in and out of phase (5 and 18
  share only 90, so they coincide twice in a three-minute run);
* an ``EchoConsumer`` subscribed to the slow beat, publishing a derived event back into the stream at
  the triggering beat's own time — two events sharing an event time, which is what the dispatch-order
  tie-break exists to make deterministic;
* the engine's and both gateways' lifecycle, recorded as ``EngineStatus`` events on the status topic.

It runs for three minutes by default, slowly enough that a viewer following the recording has
something arriving every few seconds:

    pulse-echo-example                       # 180 s, into $PULSE_OUTPUT
    pulse-echo-example 600                   # ten minutes
    pulse-echo-example 180 ~/PulseOut        # an explicit output root

Then, while it is still going:

    pulse-events-viewer                      # from pulse-viewers; open the run, it follows live

Requires a JDK 17+ (the engine runs on a JVM) and the ``[jpype]`` extra:
``pip install "pulse-beacon[jpype]"``.
"""

import os
import sys

from inventzia.pulse.beacon.core.crosslanguage import jpype_host

JAVA_CLASS = "com.inventzia.pulse.beacon.core.examples.RealTimeEchoExample"

DEFAULT_SECONDS = 180


def main() -> int:
    """Run the example; returns a process exit code."""
    argv = sys.argv[1:]
    if argv and argv[0] in ("-h", "--help"):
        print(__doc__.strip())
        return 0

    seconds = str(DEFAULT_SECONDS)
    if argv:
        try:
            seconds = str(max(10, int(argv[0])))
        except ValueError:
            print(f"usage: pulse-echo-example [seconds] [outputRoot]   "
                  f"(default {DEFAULT_SECONDS}s)", file=sys.stderr)
            return 2

    # An explicit root beats $PULSE_OUTPUT, which beats the default (~/.pulse/runs). Resolved here
    # only so the reminder below can name where the run will land; the Java side decides for real.
    root = os.path.expanduser(argv[1]) if len(argv) > 1 else ""
    where = root or os.environ.get("PULSE_OUTPUT") or "~/.pulse/runs (no PULSE_OUTPUT set)"

    # flush=True: stdout is block-buffered when piped, and the JVM writes straight to fd 1 — without
    # this the banner surfaces after the run's own output, which is the wrong way round.
    print(f"Recording into {where}", flush=True)
    print(f"Running for {seconds}s — open it in pulse-events-viewer while it goes; "
          f"a run still being written is followed live.\n", flush=True)

    try:
        jpype_host.start_jvm()
    except Exception as exc:                       # noqa: BLE001 - report, don't traceback at a user
        print(f"could not start the JVM: {exc}\n"
              f"The engine runs on a JVM: point JAVA_HOME at a JDK 17 or newer.", file=sys.stderr)
        return 1

    from jpype import JClass, JString

    args = [seconds] + ([root] if root else [])
    JClass(JAVA_CLASS).main([JString(a) for a in args])
    return 0


if __name__ == "__main__":
    sys.exit(main())
