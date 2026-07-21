# SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
# Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
#
# This file is part of pulse-beacon.
#
# pulse-beacon is dual-licensed:
#   - Under the GNU Affero General Public License v3.0 or later (see LICENSE-AGPL-3.0).
#   - Under a commercial license (see LICENSE-COMMERCIAL.txt).
#     Contact operations@inventzia.com.
"""pulse-beacon engine core: actor / gateway bases, channel, and the logging facade.

    from inventzia.pulse.beacon.core import BeaconActor, BeaconGateway

Deliberately import-light — none of these pull the JVM bridges. Import the
Python-host (``crosslanguage.jpype_host``) or Java-host (``crosslanguage.jep_host``)
machinery from its own module only when you need it.
"""

from inventzia.pulse.beacon.core.actor import BeaconActor
from inventzia.pulse.beacon.core.channel import BeaconChannel
from inventzia.pulse.beacon.core.gateway import BeaconGateway
from inventzia.pulse.beacon.core.reporter import (
    CallbackReporter,
    ComponentReporter,
    LoggingReporter,
    Reporter,
    ReportLevel,
)

__all__ = [
    "BeaconActor",
    "BeaconGateway",
    "BeaconChannel",
    "Reporter",
    "ReportLevel",
    "LoggingReporter",
    "CallbackReporter",
    "ComponentReporter",
]
