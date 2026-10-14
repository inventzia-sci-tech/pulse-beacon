<!--
SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
-->
# pulse-ext-example

A reference extension package: a self-contained, downstream project that defines its own
ad-hoc datum type, `ExtendedBar`, and teaches both the Python and Java runtimes about it
without any change to pulse-data or pulse-beacon.

## Rationale

Pulse ships a standard set of event types. Additional types, carrying
application-domain-specific data, are contributed by external packages without modification to
Pulse itself.

An adopter defines them in a namespace it controls. Acme Ltd declares a provider named
`com.acme.telemetry`, with its types beneath it, released on Acme's own schedule. Pulse checks that
every type sits beneath its declaring provider and rejects conflicting registrations; it does not
authenticate ownership of a namespace, so choosing one you control is the adopter's responsibility.

A type is defined once, in a single schema. The package then names, in its own packaging
configuration, both the class describing its types and the compiled bundle carrying them, filed
under two section names that Pulse owns. Installing the package copies those declarations into
standard metadata on disk. At startup Pulse asks for the sections bearing its own names, imports
whatever is listed there, and configures both runtimes accordingly. A package is therefore found
because it declared itself at install time, not because Pulse examined it; unrelated libraries
declare nothing under those names and never appear.

Operationally: a YAML schema; `regen.sh` generates the bindings for both languages;
`pyproject.toml` declares the `datum_types` and `jvm_classpath` entry points; Java requires a
`META-INF/services` entry; the jar must be built before installation.

Before any data flows, Pulse verifies that the two runtimes agree on every type, and refuses to
start if they do not.

## Why this exists

**The problem.** Pulse ships a fixed set of core datum types (`CdfBar`, `TextMessage`,
`HeartBeat`, and so on). Real strategies need more: a venue-specific bar, an order-book
snapshot, a bespoke signal. Because the platform runs the *same* event across two languages,
a new type cannot just be a Python class. It has to exist, and agree byte-for-byte, on both
the Java engine side and the Python side, or a producer in one language could emit an event
the other cannot decode. Hand-maintaining that agreement is exactly the kind of silent drift
Pulse is built to prevent.

**The Pulse solution: an extension SPI.** SPI stands for *Service Provider Interface*: a
published contract that the platform defines and that outside packages *implement* and *plug
in*, the inverse of a normal library API you call. Pulse's SPI is a `DatumTypeProvider`. An
extension package declares one, listing the datum types it contributes (each a `type_id`, a
`type_version`, and the class that carries it). At startup, pulse-data's registry seeds its
own core provider and then *discovers* every extension provider on the runtime, in Python
through an entry point and in Java through the standard `ServiceLoader` mechanism, and merges
them into one immutable, validated registry. Each provider also publishes a **schema
manifest** (a fingerprint of every type's exact shape) computed by a shared generator so the
bytes are identical in both languages. Before any event flows, the cross-language bridge
compares the Python and Java composite fingerprints and refuses to start if they disagree,
naming the offending type. New types become first-class, and the platform stays honest about
the two runtimes matching.

**How this example does it.** From a single schema, `schemas/extended_bar.yaml`, it generates
a Python model, a Java record, and a `DatumTypeProvider` on each side (all using this
package's own provider id, `com.inventzia.pulse.ext`), wires the Python entry point and the
Java `META-INF/services` file so both runtimes discover the provider, and then runs
`ExtendedBar` end to end through the engine. It is the worked answer to "how do I add my own
data type," and the executable proof that the SPI holds across languages.

**On namespaces.** `com.inventzia.pulse.ext` is used here only because this is a first-party demo;
an adopter uses its own, say `com.acme.telemetry`. Only the two entry-point group names and the
`META-INF/services` filename are fixed, as lookup keys rather than identity, and every `TYPE_ID`
must sit under its own provider's namespace, so no package can claim another's.

`ExtendedBar` is `CdfBar` plus `bidVolume`, `askVolume`, `tradeCount`, and an optional
`venue`: a plausible microstructure bar rather than a toy.

## What it demonstrates

- **One schema, two languages.** `schemas/extended_bar.yaml` is the single source of truth.
  `regen.sh` runs the pulse-data generators against it (with this package's own
  `--provider-id com.inventzia.pulse.ext`, `--provider-class ExtDatumTypeProvider`, and
  `--package-version`) to emit the Python model, the Java record, and a generated
  `DatumTypeProvider` on each side, all baking a byte-identical schema manifest.
- **Zero-touch discovery, both halves.** Adding a type has two halves, and this package declares
  both in the same `pyproject.toml`:
  - *the types*, through the entry-point group `inventzia.pulse.datum_types` (Python) and
    `META-INF/services` (Java). The core registry seeds itself and merges whatever it discovers
    into one frozen composite.
  - *the jar that carries them*, through the entry-point group `inventzia.pulse.jvm_classpath`, so
    Beacon puts it on the JVM classpath itself.

  Verbatim, those are the two stanzas in `pyproject.toml`:

  ```toml
  [project.entry-points."inventzia.pulse.datum_types"]
  ext = "inventzia.pulse.ext.schemas.provider:ExtDatumTypeProvider"

  [project.entry-points."inventzia.pulse.jvm_classpath"]
  ext = "inventzia.pulse.ext.jvm_classpath:jars"
  ```

  **The `jvm_classpath` value is a zero-argument callable returning jar paths, not a jar
  filename.** `jars` is a function. That is deliberate: the only portable way for a package to
  locate a jar inside its own installed wheel is `importlib.resources`, which has to execute. The
  contract on that function:

  - it must **not** start a JVM, import JPype, or touch the datum registry. Beacon calls it *before*
    Java exists, and also when checking a JVM that is already running, so it must be safe to call
    more than once and at either moment.
  - it returns a sequence of paths that exist, or raises with an actionable message. Returning an
    empty list to paper over a missing jar is the one thing not to do, because the Python types stay
    registered and the failure resurfaces later as a type-universe mismatch.
  - it should be cheap. It is a lookup, not a build step.

  Nothing in pulse-data or pulse-beacon is edited to add the type, and no application has to know
  where this package keeps its jar.
- **Cross-language safety.** When the bridge starts the JVM, the type-universe gate compares the
  two composite fingerprints and refuses to run if they disagree, before any event flows.
- **It really flows.** A Python source gateway pushes `ExtendedBar` through the Java engine to
  a Python consumer, historical and real-time, with every field intact.

## Layout

Each operating mode has its own runnable entry point, in both languages, over a small shared
core:

```
schemas/extended_bar.yaml               the datum schema (single source of truth)
regen.sh                                regenerate Python + Java bindings from the schema
src/inventzia/pulse/ext/...             generated Python model + provider (PEP 420 namespace)
java/                                   Maven module: generated record + provider + META-INF/services
  .../examples/ExtendedBarRun.java        shared run logic
  .../examples/HistoricExtendedBarExample.java   runnable main, compressed time
  .../examples/RealTimeExtendedBarExample.java   runnable main, real time
examples/extended_bar_run.py            shared run logic (Python)
examples/historical_extended_bar_run.py runnable script, compressed time
examples/realtime_extended_bar_run.py   runnable script, real time
tests/                                  end-to-end regression + the classpath contribution
```

The two sides show the same `ExtendedBar` flow. The Python scripts are Python-host runs where the
Java engine is embedded in-process over the JPype bridge (Python gateway and consumer, Java
engine). The Java classes are plain `main`s running entirely in the JVM (Java gateway, engine, and
actor). Either way the extension provider is discovered through the Service Provider Interface,
in Python via its entry point and in Java via `META-INF/services`.

## Try it

Requires the `pulse` conda env (pulse-data and pulse-beacon installed).

**Python-host runs** (Python components, embedded Java engine):

```bash
mvn -f java/pom.xml package                        # build the extension jar FIRST
pip install --no-deps -e .                         # register the entry points
python examples/historical_extended_bar_run.py     # compressed time
python examples/realtime_extended_bar_run.py       # real time
pytest tests/                                      # regression
```

Build the jar before installing. Once the package is installed it declares the jar to Beacon, so
*any* Beacon run in that environment expects it to exist and fails with an actionable error if it
does not. Installing first and building later leaves a window where unrelated runs fail.

`--no-deps` is used because pulse-data and pulse-beacon are already present in the `pulse` env as
editable installs from the sibling checkouts; without it, pip would try to resolve them from PyPI
and could replace a checkout you are working on. It is a convenience of this dev layout, not a way
to avoid declaring dependencies: the declared `pulse-data` range is real and must be kept current.

**Pure-Java runs.** `HistoricExtendedBarExample` and `RealTimeExtendedBarExample` are ordinary
`main`s. Note that automatic jar discovery is a feature of the **Python-host** bootstrap, which is
what assembles the classpath there. A pure-Java run has no such bootstrap: you pass `-cp` yourself,
exactly as below, and Java's `ServiceLoader` then finds the provider inside whatever jars you
listed. In Eclipse, import `java/` as a Maven project (it depends on `pulse-beacon-core`, which
brings the engine, pulse-data, and the logging backend), open either class, and use *Run As* or
*Debug As > Java Application* to step through it. From the command line:

```bash
mvn -f java/pom.xml package                        # or: mvn -f java/pom.xml compile
CP="../../src/inventzia/pulse/beacon/_runtime/pulse-beacon-runtime.jar:java/target/pulse-ext-example-0.1.0.jar"
java -cp "$CP" com.inventzia.pulse.ext.examples.HistoricExtendedBarExample   # compressed time
java -cp "$CP" com.inventzia.pulse.ext.examples.RealTimeExtendedBarExample   # real time
```

Both entry points print `EXTENDED BAR RUN OK`. The Maven build needs `pulse-data` and
`pulse-beacon-core` in your local Maven repository; from a source checkout install them once with
`mvn -f ../../../pulse-data/pom.xml install` and `mvn -f ../../core/java/pom.xml install`
(or import those projects into the same Eclipse workspace, where m2e resolves them directly).

To regenerate the bindings after editing the schema, run `./regen.sh`.

## How the two runtimes stay in step

Installing this extension configures **both** runtimes at once, so the core pulse-beacon examples
go on working alongside it. That was not always so, and the mechanism that makes it so is worth
understanding, because it is also what catches a genuine disagreement between the two sides.

If the two sides ever do diverge, the run stops at startup with something like:

```
TypeUniverseMismatch: cross-language datum-type universe mismatch (python=ad9d823d, java=64d6f05d):
  com.inventzia.pulse.ext.schemas.ExtendedBar: only in Python
```

That is not a bug. It is the cross-language safety gate doing its job, and these days you should
only see it if something is genuinely wrong, such as a jar built from a different schema than the
installed Python package.

### Why the gate exists

Events cross the Python/Java boundary as self-describing tagged JSON
(`{"typeId": "...", "payload": {...}}`). To turn such a message back into an object, the receiving
side must know that `typeId`. So both sides keep a registry of the datum types they know, and each
side boils its whole registry down to one short **fingerprint** (a hash of every type's exact
shape). The instant the JVM starts, **before a single event is allowed to flow**, the two
fingerprints are compared:

- **Match** means both runtimes provably agree on every type, so the run proceeds.
- **Mismatch** means the two registries are not identical. That covers a type one side knows and
  the other does not, **and equally a type both sides know in different shapes**: the same
  `TYPE_ID` generated from a changed schema, or at a different `TYPE_VERSION`, gives a different
  fingerprint. The second case is the more dangerous one, because such a message would otherwise
  decode without error into the wrong values. The gate stops immediately and names the offending
  type, rather than letting the run start and then blow up, or silently misdecode, later when that
  type first appears mid-run.

Failing fast at startup turns a scary, data-dependent, mid-run failure into a boring, obvious error
message at launch.

### Both halves, declared together

The Python side and the Java side are set by two switches, and the point is that one install moves
both:

- **Python's** known types come from what is **installed** in the environment. Installing this
  package registers `ExtendedBar` through its `inventzia.pulse.datum_types` entry point, so every
  Python run in that environment sees it.
- **Java's** known types come from **which jars are on the JVM classpath**. This package declares
  its jar through `inventzia.pulse.jvm_classpath`, so Beacon discovers it and the Java switch
  follows the environment exactly as the Python one does.

Both declarations live in the same `pyproject.toml`, next to each other, because they are two halves
of one decision. An application calls plain `start_jvm()` and passes no paths.

**This example is not a packaging recipe.** It is installed editable from a source checkout, and its
contribution therefore locates a jar that Maven built in `java/target/`. Its `pyproject.toml` does
**not** bundle that jar, so a wheel built from it would install without one. A package meant to be
distributed must ship the jar as **package data**, the way pulse-beacon ships its own runtime jar
(`inventzia.pulse.beacon._runtime`, declared under `[tool.setuptools.package-data]` and staged into
the tree before the wheel is built), and should fail its own build if the jar is absent rather than
publish a wheel that cannot work. What this example demonstrates is discovery and composition; the
distributable-artifact half is a separate exercise.

| | Python universe | Java universe | Gate |
|---|---|---|---|
| any run in the environment | core + ext | core + ext (jar discovered) | matches, runs |

### Choosing a tier

```bash
# install the extension: its types AND its jar become part of every run in the environment
pip install --no-deps -e .

# remove it: back to core only, on both sides together
pip uninstall -y pulse-ext-example
```

Uninstalling is how you *choose* a tier, not how you repair a mismatch. This is how a real
deployment behaves: an extension is present because its package is installed, and installing it
configures both runtimes together.

**Both take effect in a fresh process.** Installing or removing a package does not reconfigure a
JVM that is already running, nor a datum-type registry that has already been built and frozen.
Restart the process after changing what is installed. In a notebook that means restarting the
kernel, which is also why `start_jvm()` checks a running JVM's classpath against the current
contributions and tells you to restart rather than pretending to add to it.

## Historical note: when the jar was the application's problem

Worth recording, because older code and the git history both show the earlier shape.

Originally only the *types* were declared by the package. The *jar carrying them* was supplied by
the application, per run, by hand:

```python
_EXT_JAR = Path(__file__).resolve().parents[1] / "java" / "target" / "pulse-ext-example-0.1.0.jar"
jpype_host.start_jvm(extra_classpath=[_EXT_JAR])      # the old way
```

So installing the package changed what Python knew but not what Java knew, and any run that did not
pass the jar had a core-only JVM while Python still carried the extension:

| | Python universe | Java universe | Gate |
|---|---|---|---|
| extension example (passed the jar) | core + ext | core + ext | matches, runs |
| core example (passed nothing) | core + ext (still installed) | core only | mismatch, stops |

This document used to present that as a "type-universe toggle" and told readers to uninstall the
extension in order to run the core examples. It was a workaround documented as a feature. Four
things were wrong with it:

1. **It put a packaging detail into application code.** Every application, example and notebook had
   to know a filesystem path to another package's jar, which is the package's own business.
2. **It required manual coordination.** Two extensions were not strictly mutually exclusive: a
   caller who knew about both could pass both jars in one `extra_classpath`. But it was the
   *caller's* job to know, and a JVM's classpath is fixed at startup, so whoever started it first
   had to have assembled the complete list. No package could contribute on its own behalf.
   Automatic collection removes that coordination: each package declares its own jar and Beacon
   gathers them.
3. **It made the safety gate report configuration errors.** The fingerprint gate exists to catch
   real schema drift. Its most frequent trigger was instead "you forgot a path", which teaches
   people to read a genuine safety mechanism as noise.
4. **The failure was late and misattributed.** You got a mismatch naming a *type* when the cause was
   a *missing jar*, and only after the JVM had started.

Note that the old failure was never "a core example used a smaller subset of the same JVM". Its JVM
was core-only while Python carried the extension, and the gate compares for exact equality in both
directions, so "the extension is just a superset" was never a pass.
