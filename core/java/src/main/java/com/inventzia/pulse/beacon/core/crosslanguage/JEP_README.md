<!--
SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
-->

# JEP setup — running Python components under the JVM (Java-host direction)

This is the environment guide for the **Java-host** cross-language direction: the JVM
embeds CPython via **[JEP](https://github.com/ninia/jep)** and runs Python
`beacon.core` components in-process. The code side is `JepLauncher.java` (this
folder) plus the Python factory `crosslanguage/jep_host.py`; this document is only
about getting the **native environment** wired so `new SubInterpreter()` succeeds.
For the design, see `docs/cross-language-python-inprocess.md` §8b. The Python-host
(JPype) direction needs none of this — JEP is only for embedding Python **in** Java.

Reference example: `examples.HistoricRunJepExample` (asserts `PARITY OK`).

## Why it needs setup

Unlike JPype (a pure-Python pip wheel), JEP compiles a **native library** and the JVM
must be told where to find it, plus where the embedded Python and its DLL/.so live.
JEP's native lib is **platform- and Python-version-specific**, so a Linux build and a
Windows build are separate artifacts — you cannot share one across OSes.

## Versions (keep these aligned)

| | value |
|---|---|
| JEP | **4.3.1** (matches `black.ninia:jep:4.3.1` in `pom.xml`) |
| Python | **3.11** (the interpreter JEP embeds; match major.minor to the jep build) |
| Java | 17+ (build and run) |
| Python runtime deps | **`pydantic`** only (everything else the components import is stdlib) |

The `jep-4.2.2.jar` is plain Java bytecode and is **platform-independent** — the same
jar is used on both OSes; only the native lib differs (`libjep.so` vs `jep.dll`).

## The knobs, per platform

| concern | Linux | Windows |
|---|---|---|
| native lib file | `libjep.so` | `jep.dll` |
| dir with the native lib (→ `-Djava.library.path`) | `$CONDA_PREFIX/lib/python3.11/site-packages/jep` | `%PYDIR%\Lib\site-packages\jep` |
| lib **search path** var | `LD_LIBRARY_PATH` = `$CONDA_PREFIX/lib` + jep dir | `PATH` = Python dir + jep dir |
| embedded Python home | usually auto (conda) | set `PYTHONHOME` = `%PYDIR%` |
| classpath | jep jar + module + runtime deps | same |
| app arg | `-Dpulse.repo.root=<New>` | `-Dpulse.repo.root=C:\...\New` |

`<New>` / `C:\...\New` is the directory that contains **both** `pulse-beacon` and
`pulse-data`; the launcher puts their `src/` roots on the interpreters' `sys.path`
so the public `inventzia.pulse.*` packages resolve (redundant if the packages are
pip-installed into the embedded interpreter's env — then no path is needed). On this
project it is `/mnt/c/AlgoInfra/New` in WSL, i.e. `C:\AlgoInfra\New` on Windows.

---

## Linux (WSL) — the turnkey path

The `pulse` conda env already carries a JDK + Maven; `jep` is in
`pulse-beacon/py_environment.yml`.

1. Ensure the env has jep (built at `conda env update`, or manually):
   ```bash
   conda run -n pulse pip install "jep==4.2.2"
   ```
   Maven resolves the compile-time `black.ninia:jep:4.2.2` from **Maven Central** — no
   `install-file` needed. The runtime jep pinned above matches that version (see the pom).
2. Build the module, then run:
   ```bash
   cd core/java
   conda run -n pulse ./run-jep-example.sh          # sets classpath + java.library.path + LD_LIBRARY_PATH
   ```
   Automated equivalent (from `pulse-beacon`):
   ```bash
   conda run -n pulse python -m pytest tests/test_historic_run_jep.py
   ```

`run-jep-example.sh` derives the jep dir from `$CONDA_PREFIX`, so nothing is hard-coded.

---

## Windows — for debugging in Eclipse / IntelliJ

### 1. Prerequisites
- **Python 3.11 for Windows** (python.org or Miniconda-for-Windows). Note its folder,
  e.g. `C:\Python311` — referred to below as `%PYDIR%`.
- **A JDK** with `JAVA_HOME` set (Temurin 17/21). Needed to *build* jep.
- **Microsoft C++ Build Tools** — install *"Build Tools for Visual Studio" → Desktop
  development with C++*. Do the pip step from the **"x64 Native Tools Command Prompt
  for VS"** so the compiler is on `PATH`. (This is the main gate on Windows.)

### 2. Build/install jep + pydantic
In that VS command prompt, with `JAVA_HOME` set:
```bat
set JAVA_HOME=C:\path\to\jdk
"%PYDIR%\python.exe" -m pip install "jep==4.2.2" pydantic
```
Artifacts: `%PYDIR%\Lib\site-packages\jep\` — holds `jep-4.2.2.jar` and `jep.dll`.
Call that folder `%JEPDIR%`.

### 3. Let the IDE compile
Maven resolves `black.ninia:jep:4.2.2` from Maven Central — no `install-file` step. Just
Maven-update the project so `JepLauncher` / `HistoricRunJepExample` compile. (The Central
jar is compile-only; at runtime the native `%JEPDIR%\jep.dll` + `jep-4.2.2.jar` are used.)

### 4. Validate JEP alone first
Create a throwaway `JepSmoke.java` to isolate the native wiring from the app:
```java
import jep.Interpreter; import jep.SubInterpreter;
public class JepSmoke {
    public static void main(String[] a) throws Exception {
        try (Interpreter i = new SubInterpreter()) {
            i.exec("import sys");
            i.exec("print('hello from Java-hosted CPython', sys.version.split()[0])");
        }
    }
}
```
Run config:
- **VM arguments:** `-Djava.library.path="%JEPDIR%"`
- **Environment:** `PATH` = `%PYDIR%;%JEPDIR%;${env_var:PATH}`, and `PYTHONHOME` = `%PYDIR%`

`hello from Java-hosted CPython 3.11` ⇒ the hard part is done.

### 5. Run `HistoricRunJepExample`
Eclipse **Run → Run Configurations → Java Application**:
- **Main class:** `com.inventzia.pulse.beacon.core.examples.HistoricRunJepExample`
- **VM arguments:**
  `-Djava.library.path="%JEPDIR%" -Dpulse.repo.root="C:\AlgoInfra\New"`
- **Environment:** `PATH` = `%PYDIR%;%JEPDIR%;${env_var:PATH}`, `PYTHONHOME` = `%PYDIR%`
- **Classpath:** the project's, plus the jep jar (from the Maven `provided` dep — resolved
  from Central; if the run classpath lacks it, *Classpath → User Entries → Add External JARs →*
  `%JEPDIR%\jep-4.2.2.jar`).

Expected: the interleaved engine + interpreter log, ending in `PARITY OK`.

---

## Troubleshooting

| symptom | cause / fix |
|---|---|
| `UnsatisfiedLinkError: no jep in java.library.path` | `-Djava.library.path` not pointing at the jep dir (the one with `libjep.so`/`jep.dll`); or a **platform mismatch** (a Linux `libjep.so` cannot load in a Windows JVM, and vice versa — build jep on the OS you run). |
| `Error: The main Python interpreter previously failed to initialize` | follow-on from the above once the first `SubInterpreter` failed; fix the native path and rerun. |
| interpreter loads but `import pydantic` / component build fails | the embedded Windows/Linux Python is missing `pydantic` — `pip install pydantic` into **that** Python. |
| `python311.dll` not found (Windows) | Python dir not on `PATH`, or `PYTHONHOME` unset. |
| `ModuleNotFoundError: core` / `datum` / `schemas` | `-Dpulse.repo.root` wrong — it must point at the folder containing `pulse-beacon` **and** `pulse-data`. |
| engine hangs at `STARTED`, `printer received 0` | a streamer thread died before acking startup (usually one of the above) — check the stack trace above the parity line. |

## How the code uses this
`JepLauncher(List.of(beaconRoot, dataRoot))` prepends those to each interpreter's
`sys.path`; `launch(Component)` opens one `SubInterpreter` per component thread and
calls `jep_host.run_consume` / `run_produce`. This README only ensures the JVM can
*create* those interpreters — the native `libjep`/`jep.dll` + libpython being findable.

## Logging: Python logs through the Java logger
Under JEP, `JepLauncher` redirects each interpreter's Python logging **to the Java
logger** (`SLF4J`/Logback): it installs a `CallbackReporter` as the Python default
sink (`reporter.set_default_reporter(CallbackReporter(Slf4jReporter.shared()))`),
which forwards every record to the Java `Reporter` (via its `reportFromForeign`
default method, passing the level by name). So Python and Java components share **one
ordered, identically-formatted console stream** instead of two separately-buffered
ones — the line's timestamp is stamped by the Java side when it is written.
(No component changes: this just swaps the `Reporter` sink, which the facade is built
to allow. The Python-host/JPype direction is unaffected — it keeps using Python's own
`logging`.)
