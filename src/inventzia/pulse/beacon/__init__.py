# SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
# Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
#
# This file is part of pulse-beacon.
#
# pulse-beacon is dual-licensed:
#   - Under the GNU Affero General Public License v3.0 or later (see LICENSE-AGPL-3.0).
#   - Under a commercial license (see LICENSE-COMMERCIAL.txt).
#     Contact operations@inventzia.com.
"""pulse-beacon: the event-driven engine core and in-process cross-language bridge.

See :mod:`inventzia.pulse.beacon.core` for the public engine API (actor / gateway
bases, channel, logging facade).

``inventzia`` and ``inventzia.pulse`` are PEP 420 namespace packages shared with
pulse-data; from here inward the packages are regular packages with deliberate
exports.
"""
