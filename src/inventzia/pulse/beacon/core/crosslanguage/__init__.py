# SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
# Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
#
# This file is part of pulse-beacon.
#
# pulse-beacon is dual-licensed:
#   - Under the GNU Affero General Public License v3.0 or later (see LICENSE-AGPL-3.0).
#   - Under a commercial license (see LICENSE-COMMERCIAL.txt).
#     Contact operations@inventzia.com.
"""In-process cross-language bridge: the JVM-agnostic streamer plus the JPype
(Python-host) and JEP (Java-host) bootstraps.

Kept import-light on purpose — the ``jpype_host`` / ``jep_host`` modules pull
heavy or native dependencies, so import the specific host you need rather than
this package.
"""
