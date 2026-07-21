# SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
# Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
#
# This file is part of pulse-beacon.
#
# pulse-beacon is dual-licensed:
#   - Under the GNU Affero General Public License v3.0 or later (see LICENSE-AGPL-3.0).
#   - Under a commercial license (see LICENSE-COMMERCIAL.txt).
#     Contact operations@inventzia.com.
"""Pytest bootstrap for the pulse-beacon Python tests.

These tests run against the *installed* packages (``inventzia.pulse.beacon`` and
``inventzia.pulse.data``), not the source tree. Install both editable into the
active environment first:

    pip install -e ./pulse-data -e ./pulse-beacon

Deliberately no ``sys.path`` manipulation here: a test must not pass merely
because a repository root happens to be importable (a distribution-correctness
guarantee — see PackagingAndPublishingRelease.md §7).

Integration tests (the cross-language runs) are marked ``integration`` and need a
Beacon classpath + a JVM. They resolve the classpath via
``jpype_host.resolve_classpath()``, so they exercise the **wheel-bundled runtime
jar** when present (installed artifact) and fall back to source-tree staged jars.
A missing prerequisite is a *skip* locally but a *failure* when
``PULSE_REQUIRE_INTEGRATION`` is set (release CI must test, not skip). Deselect
them for a quick unit run with ``pytest -m "not integration"``.
"""

import os

import pytest


def pytest_configure(config):
    config.addinivalue_line(
        "markers",
        "integration: cross-language test needing a Beacon classpath + JVM "
        "(the JEP variant also needs a source-tree Java build)",
    )


def _missing_prereq(reason: str):
    """Skip locally, but fail in release CI (``PULSE_REQUIRE_INTEGRATION`` set)."""
    if os.environ.get("PULSE_REQUIRE_INTEGRATION"):
        pytest.fail(f"integration prerequisite missing: {reason}", pytrace=False)
    pytest.skip(reason)


@pytest.fixture(scope="session")
def require_prereq():
    """Callable ``(reason) -> None`` — skip locally, fail in release CI."""
    return _missing_prereq


@pytest.fixture(scope="module")
def beacon_jvm():
    """Resolve the Beacon classpath and start the embedded JVM.

    Uses ``resolve_classpath()``, so the wheel-bundled runtime jar is accepted and
    the test exercises the installed artifact instead of skipping on the old
    staged-jars-only check. Returns the classpath source description.
    """
    from inventzia.pulse.beacon.core.crosslanguage import jpype_host

    try:
        _, source = jpype_host.resolve_classpath()
    except FileNotFoundError as exc:
        _missing_prereq(f"no Beacon classpath (bundled jar or staged jars): {exc}")
    jpype_host.start_jvm()
    return source
