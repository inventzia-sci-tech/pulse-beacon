# SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
# Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
#
# This file is part of pulse-beacon.
#
# pulse-beacon is dual-licensed:
#   - Under the GNU Affero General Public License v3.0 or later (see LICENSE-AGPL-3.0).
#   - Under a commercial license (see LICENSE-COMMERCIAL.txt).
#     Contact operations@inventzia.com.
"""
Python-host bootstrap: start an embedded JVM (via JPype) on the Beacon classpath.

This is the only direction-specific piece for the Python-host launcher. The
classpath is resolved in three parts, in order:

  1. **Installed package (zero-config):** a single shaded *runtime jar* — Beacon
     plus its ordinary Java dependencies — bundled inside the wheel at
     ``inventzia.pulse.beacon._runtime``. Located via ``importlib.resources``, so
     ``pip install pulse-beacon`` needs no staged jars, ``PYTHONPATH``, or Maven.
  2. **Source checkout (dev):** the Maven-produced release jar set staged into
     ``<pulse-beacon>/core/java/jars/`` (the module jar plus runtime deps).
  3. **Contributions from installed domain packages:** jars declared through the
     ``inventzia.pulse.jvm_classpath`` entry-point group (see
     :func:`discover_contributed_jars`). This is what lets a package such as
     pulse-finance ship its own datum types without the *application* having to
     locate a jar and pass ``extra_classpath``. Discovery of the jars mirrors the
     existing discovery of the datum types themselves, which already happens
     through the ``inventzia.pulse.datum_types`` group: without it the Python side
     can find a provider whose Java classes the JVM cannot load.

JPype finds the JVM itself from ``JAVA_HOME`` — a jar is not a JVM, so a JDK/JRE
17+ must be present (see PackagingAndPublishingRelease.md §3). Everything above
the bootstrap (actors, gateways, channel, dispatch, streamer) is JVM-agnostic
and unchanged from the Java-host (JEP) path.
"""

import glob
import os
from pathlib import Path

from inventzia.pulse.beacon.core.reporter import ComponentReporter

_log = ComponentReporter("jpype-host")

# The wheel-bundled shaded runtime jar (built by core/java `mvn -Pruntime-jar`
# and copied here — see pulse-beacon/build-runtime-jar.sh).
_RUNTIME_PACKAGE = "inventzia.pulse.beacon._runtime"
_RUNTIME_JAR = "pulse-beacon-runtime.jar"

#: Entry-point group through which an installed package contributes jars to the JVM classpath.
#:
#: Each entry point must reference a **callable** taking no arguments and returning a sequence of
#: jar paths that already exist. A callable rather than a static attribute because the only portable
#: way for a package to locate its own bundled jar is ``importlib.resources``, which has to run.
#:
#: The callable is invoked **before the JVM exists**, so it must not start a JVM, import JPype, or
#: touch the datum-type registry. Returning an empty sequence is not an error: a data-only install
#: legitimately contributes nothing.
_CLASSPATH_GROUP = "inventzia.pulse.jvm_classpath"


class ClasspathContributionError(RuntimeError):
    """An installed package's classpath contribution could not be used.

    Always names the responsible distribution. Raised *before* the JVM starts, because the
    alternative is a ``NoClassDefFoundError`` surfacing much later with nothing to attribute it to.
    """


def _distribution_of(entry_point) -> str:
    dist = getattr(entry_point, "dist", None)
    return getattr(dist, "name", None) or "<unknown distribution>"


def _discover_contributions() -> list[tuple[str, str]]:
    """``[(jar path, contributing distribution)]`` in deterministic order.

    Every failure, including one raised while *iterating* a contributor's result or converting an
    element to a path, is reported as :class:`ClasspathContributionError` naming the distribution.
    An earlier version validated only a bare string and iterated outside the wrapper, so a
    contributor returning ``42`` surfaced as an unattributed ``TypeError: 'int' object is not
    iterable``, and a generator failing part-way through did the same.

    Order is by entry-point name, then distribution. Entry-point iteration order is otherwise
    unspecified, and an unstable classpath makes version-shadowing bugs reproduce only on some
    machines.
    """
    from importlib.metadata import entry_points

    contributions: list[tuple[str, str]] = []
    found = sorted(entry_points(group=_CLASSPATH_GROUP),
                   key=lambda ep: (ep.name, _distribution_of(ep)))

    for ep in found:
        dist = _distribution_of(ep)
        who = f"{dist} (entry point '{ep.name}' in '{_CLASSPATH_GROUP}')"

        try:
            contribute = ep.load()
        except Exception as exc:
            raise ClasspathContributionError(
                f"{who} could not be loaded: {exc!r}. The entry point must reference an importable "
                f"callable.") from exc

        if not callable(contribute):
            raise ClasspathContributionError(
                f"{who} must reference a callable, but it resolved to "
                f"{type(contribute).__name__}. A callable is required so the package can locate its "
                f"own jar via importlib.resources at call time.")

        # One wrapper around calling, iterating and path conversion: a contributor can fail at any
        # of the three, and all three must stay attributable.
        try:
            contributed = contribute()
            if contributed is None:
                contributed = ()
            if isinstance(contributed, (str, bytes, Path)):
                raise ClasspathContributionError(
                    f"{who} returned a single {type(contributed).__name__}; it must return a "
                    f"sequence of jar paths (wrap it in a list).")
            try:
                candidates = list(contributed)
            except TypeError as exc:
                raise ClasspathContributionError(
                    f"{who} returned {type(contributed).__name__}, which is not iterable; it must "
                    f"return a sequence of jar paths.") from exc

            resolved: list[str] = []
            for candidate in candidates:
                try:
                    path = Path(os.fspath(candidate)
                                if hasattr(candidate, "__fspath__") else str(candidate))
                except Exception as exc:
                    raise ClasspathContributionError(
                        f"{who} contributed an entry that is not a usable path "
                        f"({candidate!r}): {exc!r}") from exc
                if not path.is_file():
                    raise ClasspathContributionError(
                        f"{who} contributed a jar that does not exist: {path}. The package is "
                        f"installed but its jar is missing, which usually means the wheel was "
                        f"built without it.")
                resolved.append(str(path))
        except ClasspathContributionError:
            raise
        except Exception as exc:
            raise ClasspathContributionError(
                f"{who} raised while contributing its classpath: {exc!r}") from exc

        contributions.extend((jar, dist) for jar in resolved)
    return contributions


def discover_contributed_jars() -> tuple[list[str], list[str]]:
    """Jars contributed by installed packages through :data:`_CLASSPATH_GROUP`.

    :returns: ``(jars, notes)`` where *notes* describes each contributor for logging.
    :raises ClasspathContributionError: if any contributor violates the contract; the message names
        the distribution responsible.
    """
    contributions = _discover_contributions()
    jars = [jar for jar, _ in contributions]

    notes: list[str] = []
    for _, dist in contributions:
        if dist not in notes:
            notes.append(dist)
    counts = {d: sum(1 for _, dd in contributions if dd == d) for d in notes}
    return jars, [f"{d} ({counts[d]} jar{'s' if counts[d] != 1 else ''})" for d in notes]


def _deduplicate(entries) -> list[str]:
    """Drop repeats, comparing canonicalised paths, keeping first occurrence."""
    seen: set[str] = set()
    out: list[str] = []
    for entry in entries:
        key = _normalize_classpath_entry(entry)
        if key in seen:
            continue
        seen.add(key)
        out.append(str(entry))
    return out


def bundled_runtime_jar() -> Path | None:
    """The shaded runtime jar bundled in the installed wheel, or ``None`` if absent.

    Present in an installed/built package; absent in a bare source checkout that
    hasn't run ``build-runtime-jar.sh`` (dev then falls back to the staged jars).
    """
    try:
        from importlib.resources import files
        res = files(_RUNTIME_PACKAGE).joinpath(_RUNTIME_JAR)
        path = Path(str(res))
        return path if path.is_file() else None
    except (ModuleNotFoundError, FileNotFoundError, TypeError):
        return None


def default_jars_dir() -> Path:
    """The staged release jars directory: ``<pulse-beacon>/core/java/jars``.

    Source-checkout convenience only; for an installed package this is superseded
    by the bundled runtime-jar discovery (:func:`bundled_runtime_jar`).
    """
    # .../pulse-beacon/src/inventzia/pulse/beacon/core/crosslanguage/jpype_host.py
    # parents[6] is the pulse-beacon repo root.
    beacon_root = Path(__file__).resolve().parents[6]
    return beacon_root / "core" / "java" / "jars"


def beacon_jars(jars_dir=None) -> tuple[list[str], str]:
    """Beacon's **own** jars only: explicit dir, else bundled jar, else staged jars.

    Separate from :func:`resolve_classpath` because ``jars_dir`` overrides *Beacon's* jars without
    suppressing other packages' contributions, and because the already-running-JVM path needs to
    reason about the two independently.
    """
    if jars_dir is not None:
        jars = sorted(glob.glob(str(Path(jars_dir) / "*.jar")))
        if jars:
            return jars, f"{len(jars)} jars from {jars_dir}"
        raise FileNotFoundError(f"no jars in {jars_dir}")

    bundled = bundled_runtime_jar()
    if bundled is not None:
        return [str(bundled)], f"bundled runtime jar {bundled}"

    staged = default_jars_dir()
    jars = sorted(glob.glob(str(staged / "*.jar")))
    if jars:
        return jars, f"{len(jars)} staged jars from {staged}"

    raise FileNotFoundError(
        f"no Beacon classpath found. Either install the package (bundled runtime jar), "
        f"or build the staged jars into {staged} with pulse-beacon/build-runtime-jar.sh "
        f"(or: cd core/java && mvn package -DskipTests && "
        f"mvn dependency:copy-dependencies -DoutputDirectory=jars -DincludeScope=runtime && "
        f"cp target/pulse-beacon-core-*.jar jars/)")


def resolve_classpath(jars_dir=None) -> tuple[list[str], str]:
    """The full JVM classpath: Beacon's own jars, then contributions from installed packages.

    Returns ``(jars, source_description)``. Raises ``FileNotFoundError`` if Beacon's own jars do not
    resolve, and :class:`ClasspathContributionError` if a contribution is broken.

    Order is Beacon's jars first, then contributions, because Beacon's shaded jar supplies pulse-data
    and Jackson and a contributed jar is expected to be thin. ``extra_classpath`` is appended after
    both by :func:`start_jvm`, so an explicit argument still wins.
    """
    own, source = beacon_jars(jars_dir)
    contributed, notes = discover_contributed_jars()
    jars = _deduplicate(list(own) + contributed)
    if notes:
        source += f" + contributions from {', '.join(notes)}"
    return jars, source


def _normalize_classpath_entry(entry: str) -> str:
    """Canonicalize a classpath entry for comparison (absolute path, OS-normalized case/sep)."""
    return os.path.normcase(os.path.abspath(str(entry)))


def _running_classpath_entries() -> set[str]:
    """The normalized entries on the already-running JVM's ``java.class.path``."""
    from jpype import JClass
    raw = JClass("java.lang.System").getProperty("java.class.path") or ""
    return {_normalize_classpath_entry(e) for e in str(raw).split(os.pathsep) if e}


class TypeUniverseMismatch(RuntimeError):
    """The Python and Java datum-type universes disagree; the bridge must not run.

    Raised before any event flows, so a producer cannot emit an extension datum the
    receiving runtime cannot decode (the SPI Phase 3 fail-fast gate).
    """


def start_jvm(jars_dir=None, verify=True, extra_classpath=None) -> None:
    """Start the embedded JVM on the Beacon classpath, if not already running.

    Classpath resolution order: explicit ``jars_dir`` → wheel-bundled runtime jar →
    source-tree staged jars (see :func:`resolve_classpath`). ``extra_classpath`` (a list of
    jar paths) is appended, so an extension jar contributing datum types via
    ``META-INF/services`` is discovered by the Java ``ServiceLoader``. The JVM is located via
    ``JAVA_HOME``; in the shared ``pulse`` conda env that is set automatically by the bundled
    OpenJDK, so no manual export is needed; outside it, point ``JAVA_HOME`` at any JDK 17+.
    Idempotent.

    If ``verify`` (default), the Python and Java datum-type universes are compared once
    the JVM is up, before any event flows, and a mismatch raises
    :class:`TypeUniverseMismatch` (see :func:`verify_type_universe`).

    **Reusing an existing JVM.** If a JVM is already running (a prior ``start_jvm``, a re-run
    notebook cell, a retry after a failed verification, or a host application that booted the
    JVM), this does not silently no-op: verification (when ``verify``) still runs, so a
    mismatch present now is caught rather than slipping through just because the JVM started
    earlier.

    Under Beacon's supported startup contract the classpath is fixed at ``startJVM`` and not
    extended afterwards, so anything required but absent from the running classpath raises a
    :class:`RuntimeError` rather than being silently ignored. That check covers **jars contributed
    by installed packages as well as** explicitly requested ``jars_dir`` / ``extra_classpath``
    additions. Checking only the explicit arguments would leave the no-argument call -- which is the
    normal one once packages contribute their own jars -- verifying nothing at all, exactly in the
    notebook and host-application cases where a foreign JVM is most likely.
    """
    import jpype

    if jpype.isJVMStarted():
        # Beacon does not extend a running JVM's classpath. Anything required but absent cannot be
        # honored, so fail loudly instead of silently dropping it.
        #
        # Contributed jars are checked on EVERY call, including a no-argument one: that call is the
        # normal way to start once packages contribute their own jars, and an earlier version
        # checked nothing unless jars_dir or extra_classpath was passed.
        # Keep each jar paired with whoever asked for it. A bare filename such as
        # "runtime.jar" in the error is unactionable: the point of the message is to name the
        # package whose jar is missing, not only the path.
        required: list[tuple[str, str]] = list(_discover_contributions())
        if jars_dir is not None:
            required += [(j, f"jars_dir={jars_dir}") for j in beacon_jars(jars_dir)[0]]
        required += [(str(p), "extra_classpath argument") for p in (extra_classpath or [])]

        if required:
            on_classpath = _running_classpath_entries()
            missing: list[str] = []
            seen: set[str] = set()
            for jar, owner in required:
                key = _normalize_classpath_entry(jar)
                if key in on_classpath or key in seen:
                    continue
                seen.add(key)
                missing.append(f"{jar} (from {owner})")
            if missing:
                raise RuntimeError(
                    "JVM already running and its classpath is not extended, but these jars are "
                    "required and absent from it:\n  " + "\n  ".join(missing)
                    + "\nRestart the process and call start_jvm() once, before any other JPype "
                    "use, so the full classpath (Beacon's jars plus every contributed jar) is set "
                    "at JVM startup.")
        _log.large_info("JVM already running; reusing it")
        # Verification must still run on reuse (notebooks, retries, host-app JVMs): a mismatch
        # now must not pass just because the JVM was started by an earlier call.
        if verify:
            verify_type_universe()
        return

    jars, source = resolve_classpath(jars_dir)
    if extra_classpath:
        jars = list(jars) + [str(p) for p in extra_classpath]
        source += f" + {len(extra_classpath)} extension jar(s)"

    if "JAVA_HOME" not in os.environ:
        # A jar is not a JVM. In the `pulse` env JAVA_HOME is set automatically;
        # outside it, set it explicitly.
        raise EnvironmentError(
            "JAVA_HOME is not set; run in the `pulse` conda env, or point it at a JDK 17+")

    # convertStrings=True: Java String returns (topic names, tagged JSON) come
    # back as native Python str, so json.loads / str ops work without wrapping.
    jpype.startJVM(classpath=jars, convertStrings=True)
    _log.info(f"JVM started ({source})")
    if verify:
        verify_type_universe()


def _java_registry():
    from jpype import JClass
    return JClass("com.inventzia.pulse.data.datum.DatumTypeRegistry").defaultRegistry()


def _py_type_map(registry) -> dict:
    return {tid: (ver, fp) for pi in registry.providers() for (tid, ver, fp) in pi.entries}


def _java_type_map(registry) -> dict:
    return {str(e.typeId()): (int(e.typeVersion()), str(e.fingerprint()))
            for pi in registry.providers() for e in pi.entries()}


def verify_type_universe(py_registry=None, java_registry=None) -> str:
    """Compare the Python and Java composite datum-type fingerprints; fail fast on mismatch.

    Returns the shared fingerprint on success. Raises :class:`TypeUniverseMismatch` if the
    two differ (naming the missing or incompatible types), or if either side is unverifiable
    (a provider without a manifest), which is treated as fail-closed. Defaults to each
    runtime's process-wide registry; explicit registries are accepted for testing.
    """
    from inventzia.pulse.data.datum.registry import default_registry

    py_reg = py_registry if py_registry is not None else default_registry()
    java_reg = java_registry if java_registry is not None else _java_registry()

    py_fp = py_reg.fingerprint()
    jfp = java_reg.fingerprint()
    java_fp = str(jfp) if jfp is not None else None

    if py_fp is None or java_fp is None:
        py_unv = list(py_reg.unverifiable_providers())
        java_unv = [str(x) for x in java_reg.unverifiableProviders()]
        raise TypeUniverseMismatch(
            "datum-type universe is unverifiable (a provider has no manifest): "
            f"python unverifiable={py_unv}, java unverifiable={java_unv}")

    if py_fp != java_fp:
        pym, jvm = _py_type_map(py_reg), _java_type_map(java_reg)
        diffs = []
        for tid in sorted(set(pym) | set(jvm)):
            p, j = pym.get(tid), jvm.get(tid)
            if p is None:
                diffs.append(f"  {tid}: only in Java")
            elif j is None:
                diffs.append(f"  {tid}: only in Python")
            elif p != j:
                diffs.append(f"  {tid}: differs (python v{p[0]}/{p[1][:8]}, java v{j[0]}/{j[1][:8]})")
        raise TypeUniverseMismatch(
            f"cross-language datum-type universe mismatch (python={py_fp[:12]}, java={java_fp[:12]}):\n"
            + "\n".join(diffs))

    _log.large_info(lambda: f"type universe verified: {py_fp[:12]}")
    return py_fp
