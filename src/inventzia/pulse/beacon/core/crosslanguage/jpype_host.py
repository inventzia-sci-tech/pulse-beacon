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
classpath is resolved in two ways, in order:

  1. **Installed package (zero-config):** a single shaded *runtime jar* — Beacon
     plus its ordinary Java dependencies — bundled inside the wheel at
     ``inventzia.pulse.beacon._runtime``. Located via ``importlib.resources``, so
     ``pip install pulse-beacon`` needs no staged jars, ``PYTHONPATH``, or Maven.
  2. **Source checkout (dev):** the Maven-produced release jar set staged into
     ``<pulse-beacon>/core/java/jars/`` (the module jar plus runtime deps).

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


def resolve_classpath(jars_dir=None) -> tuple[list[str], str]:
    """Resolve the Beacon classpath: explicit dir, else bundled jar, else staged jars.

    Returns ``(jars, source_description)``. Raises ``FileNotFoundError`` if none resolve.
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
    earlier. And because a running JVM's classpath cannot be changed, any requested classpath
    additions (``jars_dir`` / ``extra_classpath``) that are not already on it raise a
    :class:`RuntimeError` rather than being silently ignored.
    """
    import jpype

    if jpype.isJVMStarted():
        # A running JVM's classpath is fixed. If the caller asked to add jars that are not
        # already on it, they cannot be honored — fail loudly instead of silently dropping them.
        if jars_dir is not None or extra_classpath:
            requested = list(resolve_classpath(jars_dir)[0]) if jars_dir is not None else []
            requested += [str(p) for p in (extra_classpath or [])]
            on_classpath = _running_classpath_entries()
            missing = [j for j in requested if _normalize_classpath_entry(j) not in on_classpath]
            if missing:
                raise RuntimeError(
                    "JVM already running; its classpath cannot be changed, but start_jvm() was "
                    f"asked to add jars that are not on it: {missing}. Start the JVM once with the "
                    "full classpath (including every extension jar) before any other JPype use.")
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
