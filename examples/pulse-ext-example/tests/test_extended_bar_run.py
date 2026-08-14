# SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
# Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
"""End-to-end regression: the extension's ExtendedBar through the Java engine (JPype).

Requires the extension installed (pip install -e .) and its jar built
(mvn -f java/pom.xml package); skips otherwise.
"""

import importlib.util
from pathlib import Path

import pytest

_EXAMPLE = Path(__file__).resolve().parents[1] / "examples" / "extended_bar_run.py"


def _load_example():
    spec = importlib.util.spec_from_file_location("extended_bar_run", _EXAMPLE)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def test_historical_extended_bar_cross_language_run():
    pytest.importorskip(
        "inventzia.pulse.ext.schemas.extended_bar",
        reason="extension not installed (pip install --no-deps -e .)")
    m = _load_example()
    if not m._EXT_JAR.exists():
        pytest.skip(f"extension jar not built: {m._EXT_JAR} (mvn -f java/pom.xml package)")
    received, status, times = m.run(realtime=False)
    assert status == "COMPLETE"
    assert received == m.expected(times)
