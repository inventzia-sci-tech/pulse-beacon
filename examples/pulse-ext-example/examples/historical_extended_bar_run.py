# SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
# Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
"""Runnable historical (compressed-time) ExtendedBar example.

The extension's ExtendedBar replayed through the embedded Java engine in compressed time: the
window is entirely in the past, so the run is deterministic and finishes as fast as the bars can
be merged. See extended_bar_run.py for the shared logic and realtime_extended_bar_run.py for the
wall-clock variant.
"""

import sys

from extended_bar_run import main

if __name__ == "__main__":
    sys.exit(main(realtime=False))
