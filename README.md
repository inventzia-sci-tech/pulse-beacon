# pulse-beacon

**pulse-beacon** is a domain-agnostic, event-driven platform for routing typed events between
producers and consumers. It provides the infrastructure layer (engine, time machine, gateways, and
actor base classes) with no domain-specific vocabulary in its core. Algo-trading strategies, IoT
pipelines, or any other application domain sit on top of it.

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
- **Event-time ordering.** Events are merged and dispatched by their *logical* event time
  (`Datum.getDatumTime()`), not by arrival order. Each `TimeEvent` also records processing
  timestamps (when it entered and left the machine) for latency measurement and diagnostics.
- **Per-`(topic, keys)` routing.** Subscribers declare exactly which key values on a topic they
  care about, and the engine delivers only matching events. When several actors match one event they
  are dispatched in a deterministic order (high-priority first, then registration order), so a replay
  reproduces itself exactly.
- **Typed events.** Event schemas are defined in YAML in
  [pulse-data](https://github.com/inventzia-sci-tech/pulse-data) and code-generated for both Java
  and Python. Actors are written against concrete generated types, not untyped maps or CSV strings.
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
[`docs/cross-language-python-inprocess.md`](./docs/cross-language-python-inprocess.md).

### Folder layout

```
pulse-beacon/
├── core/
│   ├── java/     ← Maven module `pulse-beacon-core`
│   │   └── …/beacon/core/
│   │        ├── (bus contracts, engine, time machine, base classes)
│   │        ├── gateway/{file,periodic}/  ← JsonlReader/Writer, HeartBeat
│   │        ├── crosslanguage/            ← CrossLanguageActor/Gateway/Event (Java half of the bridge)
│   │        └── examples/                 ← runnable examples (see below)
│   └── python/   ← `inventzia.pulse.beacon.core`: Python actor/gateway bases,
│                   channel, dispatch, Reporter logging facade, CrossLanguageStreamer
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
  is resolved through a generated `TYPE_ID → class` registry (`DatumTypeRegistry` in Java, with a
  Python mirror). This is what the cross-language boundary (and, later, ZMQ) carries.

The JSON engine (Jackson) is hidden entirely, so **pulse-beacon does not reference Jackson at all.**
The JSONL gateways take no serializer argument; they use the shared singleton:

```java
new JsonlReaderGateway<>("reader", topic, keys, path, start, end);
```

Gateways handle serialisation at the boundary, and actors only ever see typed `Datum` values.

## Familiarizing Running Examples

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
[the cross-language spec](./docs/cross-language-python-inprocess.md)). The historical run's
parity is also asserted as a pytest:

```bash
conda run -n pulse python -m pytest tests/test_historic_run_jpype.py
```

**Experimental Java-host (JEP) counterparts** run the *same* Python components with the JVM as host (the opposite
embedding direction): `examples/HistoricRunJepExample.java` and `examples/RealTimeRunJepExample.java`,
driven by `crosslanguage/JepLauncher.java` + the Python factory `crosslanguage/jep_host.py`. JEP needs
a native setup (jar + `libjep`/`jep.dll` + libpython) — see
[`core/java/.../crosslanguage/JEP_README.md`](./core/java/src/main/java/com/inventzia/pulse/beacon/core/crosslanguage/JEP_README.md).
Run either with `core/java/run-jep-example.sh [ExampleName]`; the historical run's parity is a pytest
(`tests/test_historic_run_jep.py`). JEP is not part of release CI yet, so the beta supports JPype as
its production-facing bridge and exposes JEP for evaluation only.

## Current status

The `core/java` module is functional end-to-end in both operating modes, covered by an integration
test (`HistoricalRunTest`, 20× repeated). The in-process cross-language bridge works in **both
embedding directions**, off one shared set of Python components and streamer: the **Python-host**
(JPype) launcher and the **Java-host** (JEP) launcher each re-create `HistoricRunExample` with Python
actors and a Python source gateway, and both pass parity (the Python printer sees exactly the
all-Java event-time merge). The ZMQ out-of-process socket transport remains planned.

| Area | Components | Status |
|------|-----------|--------|
| Bus contracts | `Topic<P extends Datum>`, `Pub`, `Sub`, `Actor` | ✅ |
| Gateway | `Gateway`, `AbstractGateway`, `GatewayStatus`, `OperatingMode` | ✅ |
| Engine | `AbstractEngine`, `MultiClientEngine`, `EngineCommand` | ✅ |
| Time machine | `TimeMachine`, `TimeEvent`, `TimeEventComparator` | ✅ |
| Actors | `AbstractActor` (publish helper + lifecycle hooks) | ✅ |
| Logging | `Reporter`, `Slf4jReporter`, `ComponentReporter` (SLF4J/Logback), mirrored as a Python facade so Java and Python components log identically | ✅ |
| File / periodic gateways | `gateway.file.Jsonl*`, `gateway.periodic.HeartBeatGateway` | ✅ |
| Examples | `core.examples.*` (historic, market-data, real-time) | ✅ |
| Type registry + tagged codec | pulse-data `DatumTypeRegistry` + `DatumCodec.toTaggedJson/fromTaggedJson` (both languages) | ✅ |
| Cross-language Java half | `core.crosslanguage.CrossLanguageActor` / `CrossLanguageGateway` | ✅ |
| Python `beacon.core` | actor/gateway bases, channel, dispatch, `Reporter`/`ComponentReporter` mirror, `CrossLanguageStreamer` | ✅ |
| Python-host launchers | JPype historical run (`historic_run_jpype`, parity verified) and real-time run (`realtime_run_jpype`, mirrors `RealTimeHeartbeatExample`) | ✅ |
| Java-host launcher | Experimental JEP historical and real-time runs via `JepLauncher` + `jep_host.py`; native release CI is still pending | 🧪 experimental |
| Cross-language parity tests | JPype runs in strict installed-artifact CI; JEP has a local parity test requiring native setup | 🟡 partial |
| Socket transport | ZMQ gateways | ⏳ planned |

**Beta limitations.** `COMPRESSED_TIME` (deterministic replay) is the fully hardened path;
`REAL_TIME` is demo-grade — in particular its live event queue is **unbounded** (no backpressure),
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

The bundled JDK means `JAVA_HOME` is set automatically — no manual export. (Working only with
pulse-data? The base env alone is enough.)

## Licensing

This project is dual-licensed:

- **Open Source (AGPL v3.0 or later)**: free to use, modify, and distribute under the terms of the
  GNU Affero General Public License v3.0. See [`LICENSE-AGPL-3.0`](./LICENSE-AGPL-3.0).
- **Commercial License**: use in proprietary or closed-source projects without AGPL obligations.
  See [`COMMERCIAL.md`](./COMMERCIAL.md) for the informational summary and
  [`LICENSE-COMMERCIAL.txt`](./LICENSE-COMMERCIAL.txt) for the binding terms.

Contact: operations@inventzia.com for commercial licensing.

## Contributing

Contributions are welcome. By submitting a contribution you agree to the terms in
[`CLA.md`](./CLA.md), including the Developer Certificate of Origin sign-off and the
dual-licensing grant. CI enforces DCO sign-off on every PR commit.

## Security

Please report security vulnerabilities privately as described in [`SECURITY.md`](./SECURITY.md).
Do not open public issues for security problems.

## Trademarks

"Pulse" and "Inventzia" are trademarks of Inventzia Science and Technology Ltd. The licenses for
this software do not grant any rights to use these trademarks.
