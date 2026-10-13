# SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
# Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
#
# This file is part of pulse-beacon.
#
# pulse-beacon is dual-licensed:
#   - Under the GNU Affero General Public License v3.0 or later (see LICENSE-AGPL-3.0).
#   - Under a commercial license (see LICENSE-COMMERCIAL.txt).
#     Contact operations@inventzia.com.
"""This extension's contribution to the JVM classpath.

Declared through the ``inventzia.pulse.jvm_classpath`` entry-point group, so Beacon finds this
jar by itself and an application calls ``start_jvm()`` with no arguments and no paths. Before the
group existed, every example and application had to locate the jar and pass ``extra_classpath``,
which does not compose: a JVM's classpath is set once, so two extensions each bootstrapping their
own would be mutually exclusive.

This runs *before* the JVM exists, so it must not import JPype or touch the datum registry. It
only locates a file.
"""
from __future__ import annotations

import os
from pathlib import Path

_JAR_NAME = "pulse-ext-example-0.1.0.jar"
#: Package data location used by an installed (non-editable) wheel.
_RUNTIME_PACKAGE = "inventzia.pulse.ext._runtime"


def jars() -> list[str]:
    """The jars this extension contributes, or an empty list if none is available.

    Returns empty rather than raising when the jar is absent, so a source checkout that has not
    run Maven still imports. Beacon then fails on the *type universe* mismatch, which names the
    types that are missing, instead of on a path, which would not.
    """
    for candidate in (_bundled(), _built_in_checkout()):
        if candidate is not None and candidate.is_file():
            return [str(candidate)]
    return []


def _bundled() -> Path | None:
    """The jar shipped as package data in an installed wheel."""
    try:
        from importlib.resources import files
        return Path(str(files(_RUNTIME_PACKAGE).joinpath(_JAR_NAME)))
    except (ModuleNotFoundError, FileNotFoundError, TypeError):
        return None


def _built_in_checkout() -> Path | None:
    """Maven's output in a source checkout: the editable-install and dev case."""
    # .../examples/pulse-ext-example/src/inventzia/pulse/ext/jvm_classpath.py
    # parents[4] is the pulse-ext-example root.
    override = os.environ.get("PULSE_EXT_EXAMPLE_JAR")
    if override:
        return Path(override)
    return Path(__file__).resolve().parents[4] / "java" / "target" / _JAR_NAME
