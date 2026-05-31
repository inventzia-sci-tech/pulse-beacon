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
event schemas. YAML definitions are code-generated into Java classes (via `jsonschema2pojo`) and
Python classes (via a custom generator). Generated Java event classes implement
`com.inventzia.beacon.core.Event`; generated Python classes are Pydantic models. Gateways handle
serialisation and deserialisation — actors never see raw bytes.

## Current status

Early development. The `core/java` module currently contains:

| Interface / Type | Status |
|-----------------|--------|
| `Event` — base interface for all events | ✅ |
| `Topic<E>` — typed routing identity | ✅ |
| `Pub`, `Sub`, `Actor` — publisher, subscriber, actor | ✅ |
| `GatewayStatus` — lifecycle state enum | ✅ |
| `Gateway` — gateway contract | ✅ |
| `OperatingMode`, `ReportLevel` enums | ⏳ |
| `TimeEvent` — transport record | ⏳ |
| `AbstractGateway` — base implementation | ⏳ |
| `TimeMachine` | ⏳ |
| `AbstractEngine`, `MultiClientEngine` | ⏳ |

Concrete gateways (ZMQ socket, file, heartbeat) and the Python core package are planned but not
yet started.

## Requirements

- Java 17 or later (uses records, sealed types)
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
