# SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
# Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
#
# This file is part of pulse-beacon.
#
# pulse-beacon is dual-licensed:
#   - Under the GNU Affero General Public License v3.0 or later (see LICENSE-AGPL-3.0).
#   - Under a commercial license (see LICENSE-COMMERCIAL.txt).
#     Contact operations@inventzia.com.
"""Carrier package for the wheel-bundled Beacon runtime jar.

A *regular* package (unlike the PEP 420 namespace packages above it) so it can
hold ``pulse-beacon-runtime.jar`` as package data, discoverable at runtime via
``importlib.resources`` — see ``crosslanguage.jpype_host.bundled_runtime_jar``.

The jar is a build artifact produced by ``pulse-beacon/build-runtime-jar.sh``
(Maven ``-Pruntime-jar`` shade); it is not committed to source control.
"""
