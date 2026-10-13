# SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
# Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
#
# This file is part of pulse-beacon.
#
# pulse-beacon is dual-licensed:
#   - Under the GNU Affero General Public License v3.0 or later (see LICENSE-AGPL-3.0).
#   - Under a commercial license (see LICENSE-COMMERCIAL.txt).
#     Contact operations@inventzia.com.
"""Jars contributed by installed packages through ``inventzia.pulse.jvm_classpath``.

The point of the mechanism: a domain package such as pulse-finance ships its own jar, and the
*application* calls ``start_jvm()`` with no arguments and no paths. Before this existed, every
extension had to locate its jar and pass ``extra_classpath``, which does not compose: a JVM's
classpath is set once, so two packages each bootstrapping their own would be mutually exclusive.

Deliberately JVM-free. Discovery, ordering, deduplication and attribution are all pure Python, and
testing them without starting a JVM keeps them fast and lets the awkward cases (a broken
contributor, a missing jar, a foreign JVM) be exercised directly rather than simulated. The
end-to-end composition proof belongs to the installed-package test in the consuming repository.
"""
from __future__ import annotations

import importlib.metadata
from pathlib import Path

import pytest

from inventzia.pulse.beacon.core.crosslanguage import jpype_host
from inventzia.pulse.beacon.core.crosslanguage.jpype_host import ClasspathContributionError


# ---------------------------------------------------------------------------
# Fakes: an entry point is only a name, a distribution and something loadable.
# ---------------------------------------------------------------------------

class _FakeDist:
    def __init__(self, name): self.name = name


class _FakeEntryPoint:
    def __init__(self, name, dist, target, load_raises=None):
        self.name = name
        self.dist = _FakeDist(dist)
        self._target = target
        self._load_raises = load_raises

    def load(self):
        if self._load_raises is not None:
            raise self._load_raises
        return self._target


@pytest.fixture
def contributions(monkeypatch):
    """Install fake entry points for the classpath group; returns a setter."""
    installed: list[_FakeEntryPoint] = []

    def fake_entry_points(*, group=None, **_):
        return list(installed) if group == jpype_host._CLASSPATH_GROUP else []

    monkeypatch.setattr(importlib.metadata, "entry_points", fake_entry_points)
    return lambda *eps: (installed.clear(), installed.extend(eps))


@pytest.fixture
def jar(tmp_path):
    """Create a file that stands in for a jar; returns a factory."""
    def make(name):
        p = tmp_path / name
        p.write_bytes(b"PK\x03\x04")          # enough to be a file; nothing reads it here
        return p
    return make


# ---------------------------------------------------------------------------
# Discovery
# ---------------------------------------------------------------------------

def test_no_contributions_leaves_the_classpath_untouched(contributions):
    contributions()
    assert jpype_host.discover_contributed_jars() == ([], [])


def test_a_contributed_jar_is_appended_and_its_package_named(contributions, jar, monkeypatch):
    j = jar("pulse-finance-core.jar")
    contributions(_FakeEntryPoint("finance", "pulse-finance", lambda: [j]))
    monkeypatch.setattr(jpype_host, "beacon_jars",
                        lambda jars_dir=None: (["/beacon/runtime.jar"], "bundled runtime jar"))

    jars, source = jpype_host.resolve_classpath()

    assert jars == ["/beacon/runtime.jar", str(j)], "Beacon's own jars must come first"
    assert "pulse-finance" in source, "the source description must name the contributor"
    assert "1 jar" in source


def test_contributions_are_collected_in_a_deterministic_order(contributions, jar, monkeypatch):
    # Entry-point iteration order is unspecified. An unstable classpath makes version-shadowing
    # bugs reproduce only on some machines, so the order is pinned by entry-point name.
    a, b, c = jar("a.jar"), jar("b.jar"), jar("c.jar")
    monkeypatch.setattr(jpype_host, "beacon_jars", lambda jars_dir=None: ([], "none"))
    for ordering in ([("zeta", c), ("alpha", a), ("mu", b)],
                     [("mu", b), ("zeta", c), ("alpha", a)]):
        contributions(*[_FakeEntryPoint(n, f"dist-{n}", (lambda p: (lambda: [p]))(p))
                        for n, p in ordering])
        jars, _ = jpype_host.resolve_classpath()
        assert jars == [str(a), str(b), str(c)], "order must not depend on entry-point iteration"


def test_the_same_jar_contributed_twice_appears_once(contributions, jar, monkeypatch):
    j = jar("shared.jar")
    contributions(_FakeEntryPoint("one", "pkg-one", lambda: [j]),
                  _FakeEntryPoint("two", "pkg-two", lambda: [j]))
    monkeypatch.setattr(jpype_host, "beacon_jars", lambda jars_dir=None: ([], "none"))
    jars, _ = jpype_host.resolve_classpath()
    assert jars == [str(j)]


def test_a_contribution_duplicating_beacons_own_jar_is_dropped(contributions, jar, monkeypatch):
    j = jar("runtime.jar")
    contributions(_FakeEntryPoint("dup", "pkg", lambda: [j]))
    monkeypatch.setattr(jpype_host, "beacon_jars", lambda jars_dir=None: ([str(j)], "bundled"))
    jars, _ = jpype_host.resolve_classpath()
    assert jars == [str(j)], "Beacon's own jar must not be listed twice"


def test_an_empty_contribution_is_not_an_error(contributions, monkeypatch):
    # A data-only install legitimately contributes no jar.
    contributions(_FakeEntryPoint("none", "pulse-finance", lambda: []),
                  _FakeEntryPoint("null", "other-pkg", lambda: None))
    monkeypatch.setattr(jpype_host, "beacon_jars", lambda jars_dir=None: (["/b.jar"], "bundled"))
    jars, source = jpype_host.resolve_classpath()
    assert jars == ["/b.jar"]
    assert "contributions from" not in source, "an empty contribution must not be announced"


def test_an_explicit_jars_dir_overrides_beacons_jars_without_suppressing_contributions(
        contributions, jar, monkeypatch, tmp_path):
    # jars_dir replaces BEACON's jars. Dropping other packages' jars too would make behaviour
    # depend on which argument the caller happened to pass.
    own = tmp_path / "dev"; own.mkdir(); (own / "dev-beacon.jar").write_bytes(b"PK\x03\x04")
    j = jar("pulse-finance-core.jar")
    contributions(_FakeEntryPoint("finance", "pulse-finance", lambda: [j]))

    jars, source = jpype_host.resolve_classpath(jars_dir=str(own))

    assert str(own / "dev-beacon.jar") in jars
    assert str(j) in jars, "a contribution must survive an explicit jars_dir"
    assert "pulse-finance" in source


# ---------------------------------------------------------------------------
# Attribution: every failure names the distribution responsible.
# ---------------------------------------------------------------------------

def test_a_contributor_that_cannot_be_imported_is_attributed(contributions):
    contributions(_FakeEntryPoint("broken", "pulse-broken", None,
                                  load_raises=ModuleNotFoundError("no module named 'nope'")))
    with pytest.raises(ClasspathContributionError) as e:
        jpype_host.discover_contributed_jars()
    assert "pulse-broken" in str(e.value)
    assert "broken" in str(e.value), "the entry-point name helps locate the declaration"


def test_a_contributor_that_is_not_callable_is_attributed(contributions):
    # A static path cannot locate a jar inside a wheel, so the contract requires a callable.
    contributions(_FakeEntryPoint("static", "pulse-static", "/some/path.jar"))
    with pytest.raises(ClasspathContributionError) as e:
        jpype_host.discover_contributed_jars()
    assert "pulse-static" in str(e.value)
    # Asserted on the explicit wording, not merely on the word "callable": without the check,
    # calling a string raises TypeError("'str' object is not callable"), whose repr contains
    # that word too, and the test would pass for the wrong reason.
    assert "must reference a callable" in str(e.value)
    assert "importlib.resources" in str(e.value), "the message should say why a callable is needed"


def test_a_contributor_that_raises_is_attributed(contributions):
    def boom(): raise RuntimeError("resource lookup failed")
    contributions(_FakeEntryPoint("angry", "pulse-angry", boom))
    with pytest.raises(ClasspathContributionError) as e:
        jpype_host.discover_contributed_jars()
    assert "pulse-angry" in str(e.value)
    assert "resource lookup failed" in str(e.value)


def test_a_missing_jar_fails_before_the_jvm_starts_and_is_attributed(contributions, tmp_path):
    # The failure this replaces is a NoClassDefFoundError surfacing mid-run with nothing to
    # attribute it to. Usually it means the wheel was built without its jar.
    absent = tmp_path / "never-built.jar"
    contributions(_FakeEntryPoint("finance", "pulse-finance", lambda: [absent]))
    with pytest.raises(ClasspathContributionError) as e:
        jpype_host.discover_contributed_jars()
    assert "pulse-finance" in str(e.value)
    assert "never-built.jar" in str(e.value)
    assert "does not exist" in str(e.value)


def test_a_bare_string_is_refused_rather_than_iterated_into_characters(contributions):
    contributions(_FakeEntryPoint("oops", "pulse-oops", lambda: "/one/jar.jar"))
    with pytest.raises(ClasspathContributionError) as e:
        jpype_host.discover_contributed_jars()
    assert "pulse-oops" in str(e.value)
    assert "sequence" in str(e.value)


# ---------------------------------------------------------------------------
# The already-running JVM. The regression guarded here: an earlier version checked
# nothing unless jars_dir or extra_classpath was passed, so the no-argument call -- the
# normal one once packages contribute jars -- verified nothing.
# ---------------------------------------------------------------------------

@pytest.fixture
def running_jvm(monkeypatch):
    """Pretend a JVM is already up with a given classpath. No JVM is started."""
    import sys, types
    verified = []

    def install(entries):
        fake_jpype = types.ModuleType("jpype")
        fake_jpype.isJVMStarted = lambda: True
        fake_jpype.startJVM = lambda **_: pytest.fail("must not start a second JVM")
        monkeypatch.setitem(sys.modules, "jpype", fake_jpype)
        monkeypatch.setattr(jpype_host, "_running_classpath_entries",
                            lambda: {jpype_host._normalize_classpath_entry(e) for e in entries})
        monkeypatch.setattr(jpype_host, "verify_type_universe",
                            lambda *a, **k: verified.append(True))
        return verified

    return install


def test_a_no_argument_call_still_checks_contributions_against_a_running_jvm(
        contributions, jar, running_jvm):
    j = jar("pulse-finance-core.jar")
    contributions(_FakeEntryPoint("finance", "pulse-finance", lambda: [j]))
    running_jvm(["/beacon/runtime.jar"])          # the contributed jar is NOT on it

    with pytest.raises(RuntimeError) as e:
        jpype_host.start_jvm()                     # no arguments at all

    message = str(e.value)
    assert "pulse-finance-core.jar" in message
    assert "Restart" in message, "the instruction must say what to do, not only what is wrong"
    assert "start_jvm() once" in message


def test_a_running_jvm_that_already_has_the_contribution_is_reused_and_still_verified(
        contributions, jar, running_jvm):
    j = jar("pulse-finance-core.jar")
    contributions(_FakeEntryPoint("finance", "pulse-finance", lambda: [j]))
    verified = running_jvm(["/beacon/runtime.jar", str(j)])

    jpype_host.start_jvm()

    assert verified == [True], "cross-language verification must still run on reuse"


def test_a_running_jvm_with_no_contributions_is_reused_unchanged(contributions, running_jvm):
    # Existing behaviour for a host-application JVM must not regress into a failure.
    contributions()
    verified = running_jvm(["/someone/elses.jar"])
    jpype_host.start_jvm()
    assert verified == [True]


def test_verification_can_still_be_declined_on_reuse(contributions, running_jvm):
    contributions()
    verified = running_jvm(["/x.jar"])
    jpype_host.start_jvm(verify=False)
    assert verified == []


def test_an_explicit_extra_classpath_is_still_checked_on_reuse(contributions, jar, running_jvm):
    contributions()
    j = jar("hand-built.jar")
    running_jvm(["/beacon/runtime.jar"])
    with pytest.raises(RuntimeError) as e:
        jpype_host.start_jvm(extra_classpath=[j])
    assert "hand-built.jar" in str(e.value)
