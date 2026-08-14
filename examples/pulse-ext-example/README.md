<!--
SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
-->
# pulse-ext-example

A reference extension package: a self-contained, downstream project that defines its own
ad-hoc datum type, `ExtendedBar`, and teaches both the Python and Java runtimes about it
without any change to pulse-data or pulse-beacon.

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

`ExtendedBar` is `CdfBar` plus `bidVolume`, `askVolume`, `tradeCount`, and an optional
`venue`: a plausible microstructure bar rather than a toy.

## What it demonstrates

- **One schema, two languages.** `schemas/extended_bar.yaml` is the single source of truth.
  `regen.sh` runs the pulse-data generators against it (with this package's own
  `--provider-id com.inventzia.pulse.ext`, `--provider-class ExtDatumTypeProvider`, and
  `--package-version`) to emit the Python model, the Java record, and a generated
  `DatumTypeProvider` on each side, all baking a byte-identical schema manifest.
- **Zero-touch discovery.** Python finds the provider via the entry-point group
  `inventzia.pulse.datum_types`; Java finds it via `META-INF/services`. The core registry
  seeds itself and merges whatever it discovers into one frozen composite. Nothing in
  pulse-data or pulse-beacon is edited to add the type.
- **Cross-language safety.** When the bridge starts the JVM with the extension jar on the
  classpath, the type-universe gate compares the two composite fingerprints and refuses to
  run if they disagree, before any event flows.
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
tests/                                  end-to-end regression (skips if the ext jar isn't built)
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
pip install --no-deps -e .                        # register the Python entry point
mvn -f java/pom.xml package                        # build the extension jar
python examples/historical_extended_bar_run.py     # compressed time
python examples/realtime_extended_bar_run.py       # real time
pytest tests/                                      # regression
```

**Pure-Java runs.** `HistoricExtendedBarExample` and `RealTimeExtendedBarExample` are ordinary
`main`s. In Eclipse, import `java/` as a Maven project (it depends on `pulse-beacon-core`, which
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
