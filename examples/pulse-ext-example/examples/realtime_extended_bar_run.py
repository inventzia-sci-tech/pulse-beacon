# SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
# Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
"""Runnable real-time ExtendedBar example.

The extension's ExtendedBar flowing through the embedded Java engine in real time: the window
spans "now", so the source gateway paces itself to the wall clock and the engine dispatches from
its live queue; the run takes a few seconds. See extended_bar_run.py for the shared logic and
historical_extended_bar_run.py for the compressed-time variant.
"""

import sys

from extended_bar_run import main

if __name__ == "__main__":
    sys.exit(main(realtime=True))
