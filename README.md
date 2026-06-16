# pulse-beacon

**pulse-beacon** is a domain-agnostic, event-driven platform for routing typed events between
producers and consumers. It provides the infrastructure layer — engine, time machine, gateways,
and actor base classes — with no domain-specific vocabulary in its core. Algo-trading strategies,
IoT pipelines, or any other application domain sit on top of it.

## What it does

pulse-beacon routes events from publishers to subscribers through a graph of **gateways**. A
gateway is any node that either produces events (a file reader, a socket, a database feed, an
exchange adapter), consumes them (a strategy, an analytics actor), or both. The engine merges
events from all active gateways into a single, deterministically-ordered stream via a
**time machine** — a sorted, watermarked queue that guarantees causal ordering regardless of
arrival jitter.

Key properties:

- **Historical simulation replay ↔ live parity.** A gateway declares whether it *drives the clock*
  (`drivesClock()`). In compressed time the time machine paces every clock-driving source so their
  events merge in exact event-time order; in real time the same actors run against a live queue.
  Moving from a historical simulation replay to a live run means swapping gateways, **not**
  rewriting actors — the run is identical by construction.
- **Event-time ordering.** Events are merged and dispatched by their *logical* event time
  (`Datum.getDatumTime()`), not arrival order. Each `TimeEvent` also records processing timestamps
  (when it entered and left the machine) for latency measurement and diagnostics.
- **Per-`(topic, keys)` routing.** Subscribers declare exactly which key values on a topic they
  care about. The engine delivers only matching events.
- **Typed events.** Event schemas are defined in YAML in
  [pulse-data](https://github.com/inventzia-sci-tech/pulse-data) and code-generated for both Java
  and Python. Actors are written against concrete generated types, not untyped maps or CSV strings.

## Architecture

### Language split

pulse-beacon's core — engine, time machine, gateways, actor base classes — is **Java**, for
throughput and deterministic concurrency. Individual **components (actors and gateways) may be
written in Java or Python**: a Python actor consumes the same typed events and a Python gateway can
be an external clock-driving source, all running against the same Java engine.

| Concern | Choice |
|---------|--------|
| Engine, time machine, gateways, base classes | **Java** (JVM concurrency, JIT, determinism) |
| Actors / gateways (your logic) | **Java or Python** — researcher-facing logic is natural in Python (NumPy, pandas, PyTorch) |
| Cross-language boundary (now) | **In-process**: Python hosts the JVM (JPype) or the JVM hosts CPython (JEP) |
| Cross-language boundary (later) | **Out-of-process**: ZMQ sockets, same programming model |
| Wire format | **Self-describing tagged JSON** via pulse-data's `DatumCodec` (`{"typeId","payload"}`) |

The in-process bridge keeps the determinism handshake cheap (a method call, not a network
round-trip), which matters for tight historical replay; ZMQ is the natural fit for live/distributed
deployment. Both sit behind one Python programming model — see
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
│                   channel, dispatch, CrossLanguageStreamer  (in progress)
└── docs/         ← design specs (e.g. the cross-language in-process spec)
```

Role-first, language-second: a module's role is visible at the top level; language is an
implementation detail of that role. `crosslanguage` is a peer of `gateway` (it holds both an actor
and a gateway), not a kind of gateway.

### Relationship to pulse-data

[pulse-data](https://github.com/inventzia-sci-tech/pulse-data) is the single source of truth for
data types. YAML schemas are code-generated into Java records (`com.inventzia.pulse.data.schemas.*`)
and Python Pydantic models (`inventzia.pulse.data.schemas.*`) — one namespace, two languages. Every
generated type implements the two-method routing contract `Datum` (`getDatumKey()`,
`getDatumTime()`); pulse-beacon routes `Datum`, nothing narrower.

**Serialization lives in pulse-data, not here.** Because pulse-data owns the types, it owns how a
`Datum` becomes JSON, via its `DatumCodec`:

- **Type-directed** — `toJson(Datum)` / `fromJson(json, Class)`, used where the type is known (e.g.
  a topic's payload type, as in the JSONL gateways).
- **Self-describing** — `toTaggedJson(Datum)` / `fromTaggedJson(json)`, which wrap the value as
  `{"typeId":"<TYPE_ID>","payload":{…}}` so a receiver recovers the type from the message. The type
  is resolved through a generated `TYPE_ID → class` registry (`DatumTypeRegistry` in Java, with a
  Python mirror). This is what the cross-language boundary (and, later, ZMQ) carries.

The JSON engine (Jackson) is hidden entirely — **pulse-beacon does not reference Jackson at all.**
The JSONL gateways take no serializer argument; they use the shared singleton:

```java
new JsonlReaderGateway<>("reader", topic, keys, path, start, end);
```

Gateways handle serialisation at the boundary; actors only ever see typed `Datum` values.

## Familiarizing — running the examples

The runnable examples in `core/java/…/beacon/core/examples/` are the fastest way to see the
platform work. Each has a `main`; run any from your IDE, or from the CLI:

```bash
# from core/java/ (uses the project's JDK + Maven; here via the conda env)
conda run -n pulse-jdk mvn -q -o dependency:build-classpath -Dmdep.outputFile=target/cp.txt
conda run -n pulse-jdk java -cp "target/classes:$(cat target/cp.txt)" \
    com.inventzia.pulse.beacon.core.examples.HistoricRunExample
```

| Example | Mode | What it demonstrates |
|---------|------|----------------------|
| **`HistoricRunExample`** | compressed-time | The canonical historical simulation replay that ties the platform together. Two JSONL `TextMessage` streams **and** a heartbeat — all clock-driving — are merged in event-time order by the time machine, delivered to a `PrintConsumer` and an `EchoConsumer`; the echoes are routed to a `PrintGateway` sink. Shows multi-source merge, an actor publishing back onto the bus, and a sink gateway. This is the run the cross-language milestone re-creates with Python components. |
| **`MarketDataReplayExample`** | compressed-time | Replays four one-minute `CdfBar` market-data bars with a 30 s heartbeat mixed in. Shows the `DatumCodec` handling rich field types a bare JSON mapper cannot (`Instant`, `LocalDate`, `BigDecimal`), and the heartbeat keeping time moving between sparse market-data bars. |
| **`RealTimeHeartbeatExample`** | real-time | A 30 s wall-clock run with three independent heartbeats (3 s / 6 s / 10 s) on three topics and gateways. Shows the real-time operating mode (live queue, paced to the wall clock) and the three cadences interleaving and coinciding at their common multiples — the live counterpart to the compressed-time runs. |

Supporting cast used by the examples:

- **`PrintConsumer`** — the simplest actor: prints each event and logs its own start-up/shut-down.
- **`EchoConsumer`** — a *publishing* actor: for each event received, emits a summary `TextMessage`
  on its own topic (the outbound side of an actor).
- **`PrintGateway`** — a *sink* gateway: a pure subscriber that prints everything delivered to it.
- **`RunUtils`** — shared helpers: `resource(...)` to locate example data, `awaitStatus(...)` to
  wait for a gateway to reach a status.

The example data lives in `core/java/src/main/resources/examples/data/` (the JSONL message and bar
fixtures), stamped in the past so the historical runs are deterministic and finish as fast as the
data merges.

## Current status

The `core/java` module is functional end-to-end in both operating modes, covered by an integration
test (`HistoricalRunTest`, 20× repeated). The cross-language bridge is in progress.

| Area | Components | Status |
|------|-----------|--------|
| Bus contracts | `Topic<P extends Datum>`, `Pub`, `Sub`, `Actor` | ✅ |
| Gateway | `Gateway`, `AbstractGateway`, `GatewayStatus`, `OperatingMode` | ✅ |
| Engine | `AbstractEngine`, `MultiClientEngine`, `EngineCommand` | ✅ |
| Time machine | `TimeMachine`, `TimeEvent`, `TimeEventComparator` | ✅ |
| Actors | `AbstractActor` (publish helper + lifecycle hooks) | ✅ |
| Logging | `Reporter`, `Slf4jReporter`, `ComponentReporter` (SLF4J/Logback) | ✅ |
| File / periodic gateways | `gateway.file.Jsonl*`, `gateway.periodic.HeartBeatGateway` | ✅ |
| Examples | `core.examples.*` (historic, market-data, real-time) | ✅ |
| Type registry + tagged codec | pulse-data `DatumTypeRegistry` + `DatumCodec.toTaggedJson/fromTaggedJson` (both languages) | ✅ |
| Cross-language Java half | `core.crosslanguage.CrossLanguageActor` / `CrossLanguageGateway` | ✅ |
| Python `beacon.core` | actor/gateway bases, channel, dispatch, `CrossLanguageStreamer` | ⏳ in progress |
| Python-host launcher | JPype historical run re-creating `HistoricRunExample` | ⏳ in progress |
| Java-host launcher | JEP | ⏳ planned |
| Socket transport | ZMQ gateways | ⏳ planned |

## Requirements

- Java 17 or later (uses records, switch/pattern matching)
- Maven 3.8+
- For Python components: Python 3.11+, plus `jpype1` (Python-host) — see the cross-language spec.

## Licensing

This project is dual-licensed:

- **Open Source (AGPL v3.0 or later)** — free to use, modify, and distribute under the terms of
  the GNU Affero General Public License v3.0. See [`LICENSE-AGPL-3.0`](./LICENSE-AGPL-3.0).
- **Commercial License** — use in proprietary or closed-source projects without AGPL obligations.
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
