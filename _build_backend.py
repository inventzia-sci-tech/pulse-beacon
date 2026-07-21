# SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
# Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
#
# This file is part of pulse-beacon.
#
# pulse-beacon is dual-licensed:
#   - Under the GNU Affero General Public License v3.0 or later (see LICENSE-AGPL-3.0).
#   - Under a commercial license (see LICENSE-COMMERCIAL.txt).
#     Contact operations@inventzia.com.
"""In-tree PEP 517 build backend: refuse to build a silently incomplete wheel.

The pulse-beacon wheel bundles a shaded Beacon *runtime jar* so JPype starts with
no external classpath. That jar is a build artifact (gitignored, produced by
``build-runtime-jar.sh``). A wheel built without it is a silently broken JPype
distribution — so this backend fails the wheel build when the jar is missing,
unless ``PULSE_ALLOW_NO_RUNTIME_JAR`` is set (an intentional jar-less dev/source
build). Only ``build_wheel`` is guarded; ``build_editable`` (``pip install -e``)
and ``build_sdist`` are unaffected, so day-to-day development needs no jar.
"""

import os
from pathlib import Path

from setuptools import build_meta as _setuptools

# Re-export every hook a PEP 517 frontend may call; only build_wheel is overridden.
from setuptools.build_meta import (  # noqa: F401
    build_editable,
    build_sdist,
    get_requires_for_build_editable,
    get_requires_for_build_sdist,
    get_requires_for_build_wheel,
    prepare_metadata_for_build_editable,
    prepare_metadata_for_build_wheel,
)

_RUNTIME_JAR = (
    Path(__file__).parent
    / "src" / "inventzia" / "pulse" / "beacon" / "_runtime" / "pulse-beacon-runtime.jar"
)


def _require_runtime_jar() -> None:
    if os.environ.get("PULSE_ALLOW_NO_RUNTIME_JAR"):
        return
    if not _RUNTIME_JAR.is_file():
        raise SystemExit(
            "pulse-beacon wheel build aborted: the bundled runtime jar is missing at\n"
            f"    {_RUNTIME_JAR}\n"
            "A wheel without it is a silently incomplete JPype distribution. Run\n"
            "    ./build-runtime-jar.sh\n"
            "before building, or set PULSE_ALLOW_NO_RUNTIME_JAR=1 for an intentionally\n"
            "jar-less dev/source build."
        )


def build_wheel(wheel_directory, config_settings=None, metadata_directory=None):
    _require_runtime_jar()
    return _setuptools.build_wheel(wheel_directory, config_settings, metadata_directory)
