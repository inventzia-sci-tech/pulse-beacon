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
**time machine** — a watermarked sorted queue that guarantees causal ordering regardless of
arrival jitter.

Key properties:

- **Backtest ↔ live parity.** A gateway declares whether it *drives the clock* (`drivesClock()`).
  Switching from historical replay to live data means swapping gateways, not rewriting actors.
- **Three-timestamp model.** Every event carries `eventTime` (when it happened), `publishedTime`
  (when it was sent), and `receivedTime` (when it was consumed). These enable latency measurement,
  out-of-order detection, and deterministic replay.
- **Per-`(topic, keys)` routing.** Subscribers declare exactly which key values on a topic they
  care about. The engine delivers only matching events.
- **Typed events.** Event schemas are defined in YAML in
  [pulse-data](https://github.com/inventzia-sci-tech/pulse-data) and code-generated for both Java
  and Python. Actors are written against concrete generated types, not untyped maps or CSV strings.

## Architecture

### Language split

| Layer | Language | Rationale |
|-------|----------|-----------|
| Engine, time machine, gateways | **Java** | Throughput and determinism; JVM concurrency primitives, JIT, mature concurrent libraries |
| Actors and strategies | **Python** | Full data-science ecosystem (NumPy, pandas, PyTorch); researcher productivity |
| Cross-language boundary | **ZMQ + JSON** | Separate processes; no in-process JVM bridge |

### Folder layout

```
pulse-beacon/
├── core/
│   ├── java/       ← Maven module: engine, time machine, gateway interfaces and base classes
│   └── python/     ← (planned) Python package mirroring core contracts
├── gateways/
│   ├── java/       ← Concrete gateways: ZMQ socket, file, database, exchange
│   └── python/     ← Python gateways: CSV/Parquet/Arrow ingestion, REST
├── actors/
│   ├── java/
│   └── python/     ← Base classes for Python actors and strategies
└── examples/
```

Role-first, language-second: the role of each module is visible at the top level; language is an
implementation detail of each role.

### Relationship to pulse-data

[pulse-data](https://github.com/inventzia-sci-tech/pulse-data) is the single source of truth for
data types. YAML schemas are code-generated into Java records and Python Pydantic models. Generated
Java classes implement `com.inventzia.pulse.data.datum.Datum` — the two-method routing contract
(`getDatumKey()`, `getDatumTime()`) the bus needs; pulse-beacon routes `Datum`, nothing narrower.

**Serialization lives in pulse-data, not here.** Because pulse-data owns the types and schemas, it
also owns how a `Datum` becomes JSON, via its `DatumCodec` — a shared singleton
(`DatumCodec.instance()`) whose whole surface is `toJson(Datum)` / `fromJson(json, type)`. It is
configured once for every field type the schemas use (JSR-310 `java.time` as ISO-8601,
`BigDecimal`, ignore-unknown-properties), and hides the JSON engine entirely:
**pulse-beacon does not reference Jackson at all.**

Concretely, the JSONL gateways take no serializer argument — they use the singleton:

```java
new JsonlReaderGateway<>("reader", topic, keys, path, start, end);
```

Gateways handle serialisation at the boundary; actors only ever see typed `Datum` values, never
raw bytes.

## Current status

The `core/java` module is functional end-to-end in both operating modes (a historic,
compressed-time replay and a real-time run), covered by an integration test.

| Area | Components | Status |
|------|-----------|--------|
| Bus contracts | `Topic<P extends Datum>`, `Pub`, `Sub`, `Actor` | ✅ |
| Gateway | `Gateway`, `AbstractGateway`, `GatewayStatus`, `OperatingMode` | ✅ |
| Engine | `AbstractEngine`, `MultiClientEngine`, `EngineCommand` | ✅ |
| Time machine | `TimeMachine`, `TimeEvent`, `TimeEventComparator` | ✅ |
| Actors | `AbstractActor` (publish helper + lifecycle hooks) | ✅ |
| Logging | `Reporter`, `Slf4jReporter`, `ComponentReporter` (SLF4J/Logback) | ✅ |
| File gateways | `gateway.file.JsonlReaderGateway`, `JsonlWriterGateway` | ✅ |
| Periodic | `gateway.periodic.HeartBeatGateway` | ✅ |
| Examples | `core.examples.*` (print/echo, historic, real-time, market-data) | ✅ |
| Socket transport | ZMQ gateways | ⏳ planned |
| Cross-language | in-process / out-of-process Python bridge | ⏳ planned |
| Python core | `pulse_beacon_core` package | ⏳ planned |

## Requirements

- Java 17 or later (uses records, switch/pattern matching)
- Maven 3.8+

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
