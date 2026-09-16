# pulse-beacon

**pulse-beacon** is a domain-agnostic, event-driven platform for routing typed events between
producers and consumers. It provides the infrastructure layer (engine, time machine, gateways, and
actor base classes) with no domain-specific vocabulary in its core. Algo-trading strategies, IoT
pipelines, or any other application domain sit on top of it.

## Quickstart

```bash
pip install "pulse-beacon[jpype]"
```

This pulls `pulse-data` (the shared event types) automatically. The `[jpype]` extra adds the
in-process Python/Java bridge; a bare `pip install pulse-beacon` omits it.

**You also need a JDK 17+**, because the engine runs on a JVM. The wheel bundles the engine as a
classpath jar, but a jar is not a JVM, so point `JAVA_HOME` at any JDK 17 or newer (check yours with
`java -version`). No Maven or separate download is required at runtime.

Then run a bundled cross-language example, a Python source gateway and consumers driving the Java
engine over the JPype bridge:

```bash
python -m inventzia.pulse.beacon.core.examples.historic_run_jpype
# ... {historic-run-jpype} : engine status: COMPLETE; printer received 10 events
# PARITY OK
```

That single run merges two Python-fed streams and a heartbeat in event-time order through the Java
time machine and delivers them to Python actors: the whole cross-language path in one command. See
[Familiarizing Running Examples](#familiarizing-running-examples) for the real-time and market-data
variants, and
[`examples/pulse-ext-example`](https://github.com/inventzia-sci-tech/pulse-beacon/tree/main/examples/pulse-ext-example/)
for adding your own datum type.

## Mission

pulse-beacon exists to make an event-driven system's behaviour **trustworthy enough to research on
and safe enough to run in production, with the same code and the same guarantees in both.** Four
principles drive every design decision:

- **Determinism.** How data travels the platform (inputs, derived system state, and client state)
  is reproducible, driven by the precise event-time of each event rather than by wall-clock arrival
  or thread scheduling. The same inputs always produce the same run.
- **Reproducibility.** A research finding must be traceable to the exact data, parameters, code, and
  strategy versions that produced it. A result you cannot regenerate is not a result.
- **Auditability.** Important decisions and lifecycle events leave durable evidence, enough to
  rebuild what each component saw and what it decided. Every event is typed, timestamped, and
  travels a single ordered path; components log their lifecycle and decisions, and any stream can be
  recorded through a sink gateway.
- **Production parity.** Research and simulation must faithfully represent the live system. Moving
  from a historical replay to a live run swaps gateways, not the actors, so what you validated is
  what you deploy.

**The solution:** one **engine** with a **time machine** underneath that merges every source into a
single, event-time-ordered stream; **gateways** that connect external sources and sinks of data at
the boundary; and **actors** that consume data and produce new derived data for other actors to
consume. Determinism is a property of the engine, not a discipline demanded of the components on top.

## What it does

pulse-beacon organises an event-driven run into three hierarchical layers:

1. **The engine** orchestrates the run. It owns the clock and the routing, merges events from every
   active source into a single, deterministically ordered stream via a **time machine** (a sorted,
   watermarked queue that guarantees causal ordering regardless of arrival jitter), and dispatches
   each event only to the components that registered interest in it.
2. **Gateways** are the boundary with the outside world. A gateway is either a *source* of data (a
   file reader, a socket, a database feed, an exchange adapter) or a *sink* for it (a writer, an
   order router, an external relay), moving data into or out of a run.
3. **Actors (consumers)** hold the logic: analytics, business rules, transformations. They consume
   events and, in turn, produce new ones, so the output of one actor can feed others through the
   engine.

Key properties:

- **Historical simulation replay ↔ live parity.** A gateway declares whether it *drives the clock*
  (`drivesClock()`). In compressed time the time machine paces every clock-driving source so their
  events merge in exact event-time order; in real time the same actors run against a live queue.
  Moving from a historical simulation replay to a live run means swapping gateways, not rewriting
  actors, so the run is identical by construction.
- **Actors are blind to run mode.** An actor cannot ask whether it is running live or as a
  replay; the operating mode is not on the actor contract. That is what makes replay/live parity a
  property of the framework rather than a rule each actor must remember to follow: a strategy that
  could branch on the mode would no longer be validated by its own simulation, and could quietly
  overfit to simulation. Observers that legitimately need the mode (logging, telemetry, tests) read
  it from the engine's public `runInfo()`, which also reports the run's datum-type universe (a
  fingerprint plus the active provider ids) for reproducibility and audit. Gateways at the system
  boundary may adapt to the mode (a realtime only gateway refuses to operate during a replay); actors may not.
- **Event-time ordering.** Events are merged and dispatched by their *logical* event time
  (`Datum.getDatumTime()`), not by arrival order. Each `TimeEvent` also records processing
  timestamps (when it entered and left the machine) for latency measurement and diagnostics.
- **Per-`(topic, keys)` routing.** Subscribers declare exactly which key values on a topic they
  care about, and the engine delivers only matching events. When several actors match one event they
  are dispatched in a deterministic order (high-priority first, then registration order), so a replay
  reproduces itself exactly.
- **Typed events.** Event schemas are defined in YAML in
  [pulse-data](https://github.com/inventzia-sci-tech/pulse-data) and code-generated into concrete
  classes for both Java and Python, so actors are written against real types, not untyped maps or
  CSV strings. The type set is open: through the `DatumTypeProvider` **SPI** (Service Provider
  Interface, a small contract a package implements and registers inside its own jar) an independent
  package can contribute its own datum types, which both runtimes discover at startup with no change
  to pulse-data or pulse-beacon. For the full description see "Adding a new data type" in the
  [pulse-data README](https://github.com/inventzia-sci-tech/pulse-data#adding-a-new-data-type), and
  the runnable [`pulse-ext-example`](https://github.com/inventzia-sci-tech/pulse-beacon/tree/main/examples/pulse-ext-example/) for a worked end-to-end example.
- **Fault isolation in dispatch.** A throwing actor is caught, logged with its stack trace, and
  skipped: the run continues and the other actors on that event are unaffected (a buggy strategy
  cannot abort a replay). A failing *subscriber/sink gateway* is treated as a broken boundary and is
  fatal, but the engine still shuts down cleanly. Either way the time machine's per-gateway write
  permit is always released (dispatch acknowledges in a `finally`) and any clock-driving source is
  unblocked on teardown, so a dispatch failure can never strand a producer or hang the run.
- **Causal publishing.** An actor may publish a derived event only at or after the event time it is
  currently handling. Same-tick (an actor re-emitting at the triggering event's time) and future
  events are allowed; a publish dated *before* the current event is acausal (it would break
  deterministic event-time ordering) and is rejected. So a replay can never produce a retroactive
  event that a live run could not.

## Architecture

### Language split

pulse-beacon's core (engine, time machine, gateways, actor base classes) is **Java**, for
throughput and deterministic concurrency. Individual **components (actors and gateways) may be
written in Java or Python**: a Python actor consumes the same typed events, and a Python gateway can
be an external clock-driving source, all running against the same Java engine.

| Concern | Choice |
|---------|--------|
| Engine, time machine, gateways, base classes | **Java** (JVM concurrency, JIT, determinism) |
| Actors / gateways (your logic) | **Java or Python**, so researcher-facing logic can use the Python stack (NumPy, pandas, PyTorch) |
| Cross-language boundary (now) | **In-process**: Python hosts the JVM (JPype), or the JVM hosts CPython (JEP) |
| Cross-language boundary (later) | **Out-of-process**: ZMQ sockets, same programming model |
| Wire format | **Self-describing tagged JSON** via pulse-data's `DatumCodec` (`{"typeId","payload"}`) |

The in-process bridge keeps the determinism handshake cheap (a method call, not a network
round-trip), which matters for tight historical replay; ZMQ is the natural fit for live or
distributed deployment. Both sit behind one Python programming model, described in
[`docs/cross-language-python-inprocess.md`](https://github.com/inventzia-sci-tech/pulse-beacon/blob/main/docs/cross-language-python-inprocess.md).

### Folder layout

```
pulse-beacon/
├── core/
│   └── java/     ← Maven module `pulse-beacon-core`
│       └── …/beacon/core/
│            ├── (bus contracts, engine, time machine, base classes)
│            ├── gateway/{file,periodic}/  ← JsonlReader/Writer, HeartBeat
│            ├── crosslanguage/            ← CrossLanguageActor/Gateway/Event (Java half of the bridge)
│            └── examples/                 ← runnable Java examples (see below)
├── src/         ← `inventzia.pulse.beacon.*` (Python): actor/gateway bases, channel,
│                   dispatch, Reporter logging facade, CrossLanguageStreamer, and core examples
├── examples/    ← standalone extension example package (pulse-ext-example)
├── tests/       ← Python test suite
└── docs/         ← design specs (e.g. the cross-language in-process spec)
```

Role-first, language-second: a module's role is visible at the top level, and language is an
implementation detail of that role. `crosslanguage` is a peer of `gateway` (it holds both an actor
and a gateway), not a kind of gateway.

### Relationship to pulse-data

[pulse-data](https://github.com/inventzia-sci-tech/pulse-data) is the single source of truth for
data types. YAML schemas are code-generated into Java records (`com.inventzia.pulse.data.schemas.*`)
and Python Pydantic models (`inventzia.pulse.data.schemas.*`), one namespace across two languages.
Every generated type implements the two-method routing contract `Datum` (`getDatumKey()`,
`getDatumTime()`), and pulse-beacon routes `Datum`, nothing narrower.

**Serialization lives in pulse-data, not here.** Because pulse-data owns the types, it owns how a
`Datum` becomes JSON, via its `DatumCodec`, in two forms:

- **Type-directed**: `toJson(Datum)` / `fromJson(json, Class)`, used where the type is known (for
  example a topic's payload type, as in the JSONL gateways).
- **Self-describing**: `toTaggedJson(Datum)` / `fromTaggedJson(json)`, which wrap the value as
  `{"typeId":"<TYPE_ID>","payload":{…}}` so a receiver recovers the type from the message. The type
  is resolved through the composite `DatumTypeRegistry` (Java; `datum/registry.py` in Python), built
  from the core provider plus any extension providers discovered via the `DatumTypeProvider` SPI.
  This is what the cross-language boundary (and, later, ZMQ) carries.

The JSON engine (Jackson) is hidden entirely, so **pulse-beacon does not reference Jackson at all.**
The JSONL gateways take no serializer argument; they use the shared singleton:

```java
new JsonlReaderGateway<>("reader", topic, keys, path, start, end);
```

Gateways handle serialisation at the boundary, and actors only ever see typed `Datum` values.

## Familiarizing Running Examples

Pulse ships **two tiers of examples**, aimed at two different questions.

- **Core examples** answer *"how does the platform work?"* They exercise the engine, time machine,
  gateways, and actors (and the cross-language bridge) using the datum types pulse-beacon already
  ships. They live inside the source tree, are importable and covered by tests, and are the fastest
  way to see a run end to end. Java: `core/java/…/beacon/core/examples/`; Python-host counterparts:
  `src/inventzia/pulse/beacon/core/examples/`. Run them straight from this repo or from an installed
  wheel.
- **The extension example** answers *"how do I add my own datum type?"* It is a standalone
  downstream package that contributes a new type through the `DatumTypeProvider` SPI without
  modifying pulse-data or pulse-beacon. Because that arms-length separation is the whole point, it
  lives *outside* the `src` tree as its own buildable and installable package under
  `examples/pulse-ext-example/`, and is deliberately not part of pulse-beacon's distribution or
  default test run. You build and install it on demand.

In short: the core examples use the types Pulse ships; the extension example shows an adopter
bringing their own. The rest of this section covers each tier in turn.

### Core examples (shipped with pulse-beacon)

The runnable examples in `core/java/…/beacon/core/examples/` are the fastest way to see the
platform work. Each has a `main`; run any from your IDE, or from the CLI:

```bash
# from core/java/ (the `pulse` env carries the JDK + Maven; see Requirements)
conda run -n pulse mvn -q -o dependency:build-classpath -Dmdep.outputFile=target/cp.txt
conda run -n pulse java -cp "target/classes:$(cat target/cp.txt)" \
    com.inventzia.pulse.beacon.core.examples.HistoricRunExample
```

| Example | Mode | What it demonstrates |
|---------|------|----------------------|
| **`HistoricRunExample`** | compressed-time | The canonical historical simulation replay that ties the platform together. Two JSONL `TextMessage` streams and a heartbeat (all clock-driving) are merged in event-time order by the time machine, delivered to a `PrintConsumer` and an `EchoConsumer`, and the echoes are routed to a `PrintGateway` sink. Shows multi-source merge, an actor publishing back onto the bus, and a sink gateway. This is the run the cross-language milestone re-creates with Python components. |
| **`MarketDataReplayExample`** | compressed-time | Replays four one-minute `CdfBar` market-data bars with a 30 s heartbeat mixed in. Shows the `DatumCodec` handling rich field types a bare JSON mapper cannot (`Instant`, `LocalDate`, `BigDecimal`), and the heartbeat keeping time moving between sparse market-data bars. |
| **`RealTimeHeartbeatExample`** | real-time | A 30 s wall-clock run with three independent heartbeats (3 s, 6 s, 10 s) on three topics and gateways. Shows the real-time operating mode (live queue, paced to the wall clock) and the three cadences interleaving and coinciding at their common multiples, the live counterpart to the compressed-time runs. |

Supporting cast used by the examples:

- **`PrintConsumer`**: the simplest actor, prints each event and logs its own start-up and shut-down.
- **`EchoConsumer`**: a *publishing* actor, emitting a summary `TextMessage` on its own topic for
  each event received (the outbound side of an actor).
- **`PrintGateway`**: a *sink* gateway, a pure subscriber that prints everything delivered to it.
- **`RunUtils`**: shared helpers, `resource(...)` to locate example data and `awaitStatus(...)` to
  wait for a gateway to reach a status.

The example data lives in `core/java/src/main/resources/examples/data/` (the JSONL message and bar
fixtures), stamped in the past so the historical runs are deterministic and finish as fast as the
data merges.

**Python-host (cross-language) counterparts** live under
`src/inventzia/pulse/beacon/core/examples/` and re-create the Java runs with Python
components over the in-process JPype bridge: `historic_run_jpype.py` (the historical replay, with
Python actors and a Python source gateway) and `realtime_run_jpype.py` (the real-time heartbeat run,
which also shows the reverse direction: a Python echo actor publishing back to a Java sink gateway).
Both need the packages installed (`pip install -e ./pulse-data -e ./pulse-beacon`, no `PYTHONPATH`
needed) and a Beacon classpath. `jpype_host` finds that classpath automatically: the wheel-bundled
shaded **runtime jar** if present (build it with `./build-runtime-jar.sh`, which the wheel embeds so
an installed `pip install pulse-beacon[jpype]` starts the JVM with no external jars), otherwise the
Maven jars staged in `core/java/jars/` (see
[the cross-language spec](https://github.com/inventzia-sci-tech/pulse-beacon/blob/main/docs/cross-language-python-inprocess.md)). The historical run's
parity is also asserted as a pytest:

```bash
conda run -n pulse python -m pytest tests/test_historic_run_jpype.py
```

**Experimental Java-host (JEP) counterparts** run the *same* Python components with the JVM as host (the opposite
embedding direction): `examples/HistoricRunJepExample.java` and `examples/RealTimeRunJepExample.java`,
driven by `crosslanguage/JepLauncher.java` + the Python factory `crosslanguage/jep_host.py`. JEP needs
a native setup (jar + `libjep`/`jep.dll` + libpython); see
[`core/java/.../crosslanguage/JEP_README.md`](https://github.com/inventzia-sci-tech/pulse-beacon/blob/main/core/java/src/main/java/com/inventzia/pulse/beacon/core/crosslanguage/JEP_README.md).
Run either with `core/java/run-jep-example.sh [ExampleName]`; the historical run's parity is a pytest
(`tests/test_historic_run_jep.py`). JEP is not part of release CI yet, so the beta supports JPype as
its production-facing bridge and exposes JEP for evaluation only.

### Extension example (a standalone package using the SPI)

[`examples/pulse-ext-example/`](https://github.com/inventzia-sci-tech/pulse-beacon/tree/main/examples/pulse-ext-example/) is a self-contained downstream package
that defines its own datum, `ExtendedBar` (a `CdfBar` with extra order-flow fields), from a single
schema, and lets both runtimes discover it through the pulse-data extension SPI (a Python
`inventzia.pulse.datum_types` entry point, a Java `META-INF/services` provider) with no change to
pulse-data or pulse-beacon.

**Why it is separate, not another core example.** Its reason to exist is to prove that an *outside*
package can extend the platform, so the arms-length separation is the demonstration: it keeps its
own namespace (`inventzia.pulse.ext`), its own `pyproject.toml` and `pom.xml`, and its own release
cadence, and it stays out of pulse-beacon's `src` tree and Maven build. Installing it deliberately
changes the datum-type universe (the cross-language gate then also requires the extension jar on the
JVM classpath), which is exactly why it is not part of pulse-beacon's distribution or default test
run. Fold it into `src` and `ExtendedBar` would ship as a de-facto core type, discovered on every
install; keeping it out is what makes it a faithful extension.

**What is there.** Each operating mode has its own runnable entry point, in both languages, over a
small shared core:

- Java (run or debug from Eclipse as a Maven project): `HistoricExtendedBarExample` and
  `RealTimeExtendedBarExample`, with shared logic in `ExtendedBarRun`.
- Python (JPype host): `historical_extended_bar_run.py` and `realtime_extended_bar_run.py`, with
  shared logic in `extended_bar_run.py`.

Each flows `ExtendedBar` through the Java engine end to end, after discovery establishes the type
universe. Build and run instructions are in its
[README](https://github.com/inventzia-sci-tech/pulse-beacon/blob/main/examples/pulse-ext-example/README.md); the high-level description of the SPI itself lives
in the pulse-data README under "Adding a new data type".

## Current status

The `core/java` module is functional end-to-end in both operating modes, covered by an integration
test (`HistoricalRunTest`, 20× repeated). The in-process cross-language bridge works in **both
embedding directions**, off one shared set of Python components and streamer: the **Python-host**
(JPype) launcher and the **Java-host** (JEP) launcher each re-create `HistoricRunExample` with Python
actors and a Python source gateway, and both pass parity (the Python printer sees exactly the
all-Java event-time merge). The ZMQ out-of-process socket transport remains planned. Datum types are
extensible: an independent package can contribute new types through the `DatumTypeProvider` SPI,
discovered at engine startup and checked across languages by a type-universe fingerprint gate before
any event flows (see the extension example above).

| Area | Components | Status |
|------|-----------|--------|
| Bus contracts | `Topic<P extends Datum>`, `Pub`, `Sub`, `Actor` | ✅ |
| Gateway | `Gateway`, `AbstractGateway`, `GatewayStatus`, `OperatingMode` | ✅ |
| Engine | `AbstractEngine`, `MultiClientEngine`, `EngineCommand` | ✅ |
| Time machine | `TimeMachine`, `TimeEvent`, `TimeEventComparator` | ✅ |
| Actors | `AbstractActor` (publish helper + lifecycle hooks) | ✅ |
| Logging | `Reporter`, `Slf4jReporter`, `ComponentReporter` (SLF4J/Logback), mirrored as a Python facade so Java and Python components log identically | ✅ |
| File / periodic gateways | `gateway.file.Jsonl*`, `gateway.periodic.HeartBeatGateway` | ✅ |
| Examples | `core.examples.*` (historic, market-data, real-time) plus the standalone `pulse-ext-example` extension demo | ✅ |
| Type registry + tagged codec | pulse-data composite `DatumTypeRegistry` (core provider + discovered `DatumTypeProvider` extensions) + `DatumCodec.toTaggedJson/fromTaggedJson` (both languages) | ✅ |
| Extensible datum types (SPI) | `DatumTypeProvider` discovery (`ServiceLoader` / Python entry points), schema-manifest fingerprint, cross-language type-universe gate at engine startup | ✅ |
| Cross-language Java half | `core.crosslanguage.CrossLanguageActor` / `CrossLanguageGateway` | ✅ |
| Python `beacon.core` | actor/gateway bases, channel, dispatch, `Reporter`/`ComponentReporter` mirror, `CrossLanguageStreamer` | ✅ |
| Python-host launchers | JPype historical run (`historic_run_jpype`, parity verified) and real-time run (`realtime_run_jpype`, mirrors `RealTimeHeartbeatExample`) | ✅ |
| Java-host launcher | Experimental JEP historical and real-time runs via `JepLauncher` + `jep_host.py`; native release CI is still pending | 🧪 experimental |
| Cross-language parity tests | JPype runs in strict installed-artifact CI; JEP has a local parity test requiring native setup | 🟡 partial |
| Socket transport | ZMQ gateways | ⏳ planned |

**Beta limitations.** `COMPRESSED_TIME` (deterministic replay) is the fully hardened path;
`REAL_TIME` is demo-grade; in particular its live event queue is **unbounded** (no backpressure),
so a live source that outpaces dispatch grows memory without limit. The intermediate `MIXED`
(replay-then-live) mode is not yet implemented. The Java-host **JEP** bridge is experimental (see
the status table). These are documented targets for a post-beta hardening pass, not blockers for the
in-process, compressed-time use the beta is scoped to.

## Requirements

Everything runs from one shared conda env, `pulse`. It is layered: pulse-data declares the minimal
base (Python 3.11 + the generators/models), and pulse-beacon enriches it with a JDK 17 + Maven (to
build the Java and to back JPype) and the `jpype1` bridge:

```bash
conda env create -f pulse-data/py_environment.yml      # creates `pulse` (base)
conda env update -f pulse-beacon/py_environment.yml     # enriches it
pip install -e ./pulse-data -e ./pulse-beacon           # editable installs (public imports)
```

The bundled JDK means `JAVA_HOME` is set automatically, so no manual export is needed. (Working only with
pulse-data? The base env alone is enough.)

## Licensing

This project is dual-licensed:

- **Open Source (AGPL v3.0 or later)**: free to use, modify, and distribute under the terms of the
  GNU Affero General Public License v3.0. See [`LICENSE-AGPL-3.0`](https://github.com/inventzia-sci-tech/pulse-beacon/blob/main/LICENSE-AGPL-3.0).
- **Commercial License**: use in proprietary or closed-source projects without AGPL obligations.
  See [`COMMERCIAL.md`](https://github.com/inventzia-sci-tech/pulse-beacon/blob/main/COMMERCIAL.md) for the informational summary and
  [`LICENSE-COMMERCIAL.txt`](https://github.com/inventzia-sci-tech/pulse-beacon/blob/main/LICENSE-COMMERCIAL.txt) for the binding terms.

Contact: operations@inventzia.com for commercial licensing.

## Contributing

Contributions are welcome. By submitting a contribution you agree to the terms in
[`CLA.md`](https://github.com/inventzia-sci-tech/pulse-beacon/blob/main/CLA.md), including the Developer Certificate of Origin sign-off and the
dual-licensing grant. CI enforces DCO sign-off on every PR commit.

## Security

Please report security vulnerabilities privately as described in [`SECURITY.md`](https://github.com/inventzia-sci-tech/pulse-beacon/blob/main/SECURITY.md).
Do not open public issues for security problems.

## Trademarks

"Pulse" and "Inventzia" are trademarks of Inventzia Science and Technology Ltd. The licenses for
this software do not grant any rights to use these trademarks.
