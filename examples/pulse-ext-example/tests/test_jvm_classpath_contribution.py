# SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
# Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
#
# This file is part of pulse-beacon.
#
# pulse-beacon is dual-licensed:
#   - Under the GNU Affero General Public License v3.0 or later (see LICENSE-AGPL-3.0).
#   - Under a commercial license (see LICENSE-COMMERCIAL.txt).
#     Contact operations@inventzia.com.
"""This extension's classpath contribution.

The regression guarded here: an earlier version returned an empty list when its jar was missing.
That looks harmless and is not. This package's *Python* datum provider stays registered either
way, so Java would come up without ``ExtendedBar`` while Python has it, which is the exact
asymmetry the entry point exists to remove. The failure then surfaced after JVM startup as a
type-universe mismatch that said nothing about a missing jar.
"""
from __future__ import annotations

import pytest

from inventzia.pulse.ext import jvm_classpath


def test_contributes_the_built_jar_when_it_exists():
    jars = jvm_classpath.jars()
    assert len(jars) == 1
    assert jars[0].endswith(jvm_classpath._JAR_NAME)


def test_a_missing_jar_raises_with_both_locations_and_the_build_command(monkeypatch, tmp_path):
    monkeypatch.setenv("PULSE_EXT_EXAMPLE_JAR", str(tmp_path / "not-built.jar"))
    monkeypatch.setattr(jvm_classpath, "_bundled", lambda: None)

    with pytest.raises(FileNotFoundError) as e:
        jvm_classpath.jars()

    message = str(e.value)
    assert jvm_classpath._JAR_NAME in message
    assert "mvn -f java/pom.xml package" in message, "the message must say how to fix it"
    assert "not-built.jar" in message, "and where it looked"
    assert "Python types are registered" in message, (
        "the message should say why an empty result would be worse than an error")


def test_the_entry_point_declares_this_callable():
    """The declaration and the implementation must not drift apart."""
    from importlib.metadata import entry_points

    eps = [e for e in entry_points(group="inventzia.pulse.jvm_classpath")
           if getattr(getattr(e, "dist", None), "name", None) == "pulse-ext-example"]
    assert eps, "pulse-ext-example must declare a jvm_classpath entry point"
    assert eps[0].load() is jvm_classpath.jars
