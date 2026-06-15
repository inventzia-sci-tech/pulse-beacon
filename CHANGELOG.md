# Changelog

All notable changes to pulse-beacon are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added (logging)

- **SLF4J + Logback logging**, wrapped behind the proprietary `Reporter` abstraction. The
  platform never calls a logging framework directly:
  - `Reporter` — the delivery-sink contract (`report` + `isEnabled`).
  - `Slf4jReporter` — default sink; routes each component's messages to its own SLF4J logger
    (`ReportLevel` → debug/info/warn/error).
  - `ComponentReporter` — the per-component handle (`log.info/warn/severe/largeInfo`), bound to a
    source name; `largeInfo(Supplier)` defers message construction on hot paths.
- Every engine, gateway, time machine, and actor owns a named `log`. Lifecycle transitions,
  registrations (both directions), `initialize`, `disconnect`, per-event dispatch (debug), and
  time-machine queueing are now logged — mirroring the old framework's per-component named loggers.
- `logback.xml` (INFO console, per-component logger names) and a quiet `logback-test.xml`.
- All `System.out.println` removed from the engine, gateways, and examples in favour of `log`.

### Fixed

- Engine bugs surfaced and fixed by the first end-to-end runs:
  - `AbstractEngine.publish` double-delivered actor-published events to subscriber gateways
    (direct call plus dispatch-loop delivery); now funnels through the dispatch loop only.
  - `processEventQueueSim` (compressed time) never handled the `SHUTDOWN` `EngineCommand`, so a
    historic run could not terminate; it now stops on that signal like the live loop.
  - Compressed-time runs never set the `startUpDone` gate, so actors received nothing; it is now
    set after `startUpActors`.
  - `TimeMachine.removeGateway` purged a disconnecting gateway's already-enqueued events, which
    raced the consumer and non-deterministically dropped a gateway's final event; those events
    are now retained and drained in order.

### Changed

- **JSONL gateways serialize via pulse-data's `DatumCodec` singleton**, not an injected
  `ObjectMapper`. The gateway constructors no longer take a mapper argument — serialization is a
  data concern owned by pulse-data, and the codec is correctly configured for every schema field
  type (java.time, `BigDecimal`). Added `MarketDataReplayExample` + `cdf_bars.jsonl` fixture as
  proof: `CdfBar` (with `Instant`/`LocalDate`/`BigDecimal`) now round-trips end-to-end, which a
  bare `ObjectMapper` could not do.
- **Package renamed** `com.inventzia.beacon.*` → `com.inventzia.pulse.beacon.*` for consistency
  with `com.inventzia.pulse.data.*`.
- **`Event` interface removed.** The bus now routes `com.inventzia.pulse.data.datum.Datum`
  (from pulse-data). Routing key and logical time come from `getDatumKey()` / `getDatumTime()`
  on the payload itself — no transport fields baked into data types.
- **`Topic<P extends Datum>(name, Class<P>)`** — payload type parameter; reflective `TYPE_ID`
  validation dropped. Routing identity is the topic name plus the payload's `Datum` methods.
- **`Pub` / `Sub`** are now `<P extends Datum>`; `publish(Topic<P>, P)` / `onEvent(Topic<P>, P)`.
- **`TimeEvent`** carries a `Datum payload`; `key()` / `eventTime()` delegate to the payload
  (single source of truth, no copy).
- **`EngineCommand`** implements `Datum` and collapses to `getDatumKey()` / `getDatumTime()`.
- pulse-beacon-core now depends on the `com.inventzia.pulse:pulse-data` artifact.

### Added

- Core Java interfaces and types under `core/java/src/main/java/com/inventzia/pulse/beacon/core/`:
  - `Event` — base interface for all events; carries `typeId()`, `schemaVersion()`, `key()`,
    `eventTime()`, `publishedTime()`, `receivedTime()` (epoch milliseconds).
  - `Topic<E extends Event>` — typed routing identity record; validates the `TYPE_ID` static
    field contract on event classes at construction time.
  - `Pub` — publisher interface: `publish(Topic<E>, E)`.
  - `Sub` — subscriber interface: `onEvent(Topic<E>, E)`.
  - `Actor` — combined publisher and subscriber: `extends Pub, Sub`.
  - `GatewayStatus` — lifecycle state enum: `BLANK → INITIALIZED → PRESTART → STARTED →
    WRAP_UP → PAUSED → FINALIZE → STOPPED → COMPLETE`.
  - `Gateway` — gateway contract: pub/sub registration, simulation window, clock-driving
    flag, connection lifecycle, identity and status.
- Root `pom.xml` added; `pulse-beacon-core` now has a parent POM managing
  Jackson, JUnit 5, and AssertJ versions centrally.
- `core.examples` package — readable, generic examples shipped with the core:
  `PrintGateway` (subscriber gateway that prints), `PrintConsumer` (consuming actor with
  lifecycle logging), `EchoConsumer` (publishing actor that emits a `TextMessage` per event),
  `HistoricRunExample` (compressed-time `main`: two readers + heartbeat + actors + echo sink),
  `RealTimeHeartbeatExample` (real-time `main`: wall-clock-paced heartbeat).
- `HistoricalRunTest` — end-to-end test (JUnit 5 + AssertJ): asserts the two-stream and
  two-stream-plus-heartbeat merges produce strict event-time order; the heartbeat case is
  repeated 20× as a determinism guard.
- Example JSONL fixtures under `src/main/resources/examples/data/`
  (`messages_one.jsonl`, `messages_two.jsonl`) — interleaved `TextMessage` streams that
  demonstrate the TimeMachine event-time merge.
- `AbstractActor`: base class for in-platform actors. `publish()` (the `Pub` method) forwards
  to the engine the actor is bound to at registration; `onEvent` abstract; overridable
  `onStartUp` / `onShutDown` lifecycle hooks. Actors implementing `Runnable` get their own thread.
- `MultiClientEngine`: binds `AbstractActor`s at registration and invokes their lifecycle
  hooks in `startUpActors` / `shutDownActors`.
- `gateway.periodic.HeartBeatGateway`: self-contained clock-driving gateway that emits
  periodic `HeartBeat` events. Works in both operating modes with no special engine support —
  blocks on the TimeMachine permit (compressed time) or sleeps to wall-clock (real time).
- `com.inventzia.beacon.core.gateway.file.JsonlReaderGateway<E>`: clock-driving
  gateway that reads JSONL files and publishes typed events; Jackson deserialization
  via `topic.eventType()` — no factory needed.
- `com.inventzia.beacon.core.gateway.file.JsonlWriterGateway`: subscribes to events
  and persists them as JSONL; parks on its own thread, written from engine dispatch
  thread via `onEvent`.
- `JsonlGatewayException`: unchecked exception for JSONL read/write failures.
- `EngineCommand`: package-private record implementing `Event`; carries `STARTUP`/`SHUTDOWN`
  signals through the TimeMachine to drive actor lifecycle at the correct simulation time.
- `AbstractEngine`: base engine extending `AbstractGateway`; `COMPRESSED_TIME` dispatch via
  `TimeMachine`, `REAL_TIME` dispatch via `LinkedBlockingQueue`; `ScheduledExecutorService`
  replaces old `java.util.Timer` for real-time startup scheduling.
- `MultiClientEngine`: concrete multi-actor engine; `registerActor` takes explicit
  subscription/publication maps; high-priority actors dispatched before normal-priority;
  runnable actors started on daemon threads.
- `AbstractGateway`: added `publisherForKey`, `registeredPublishers`, `registeredSubscribers`
  protected helpers required by the engine.
- `TimeEventComparator`: orders `TimeEvent`s by `event.eventTime()` ascending, tiebreak by
  `beginTstamp`. Replaces old arrival-time ordering with semantically correct causal ordering.
- `TimeMachine`: deterministic event merger; per-gateway `Semaphore`-based flow control replaces
  the custom `Lock`; `ArrayList` with binary-search insertion replaces `WaterMarkedSortedList`;
  `ReusableTimeEventBuffer` dropped (records are cheap, no pooling needed).
- `Reporter` interface: out-of-band operator alert channel, distinct from application logging.
- `AbstractGateway`: base implementation of `Gateway`; routing tables, lifecycle hooks
  (`initialize`, `connect`, `disconnect`), mutual registration with circular-registration
  guard, `OperatingMode` determination, and `Reporter` integration.
- `OperatingMode` enum: `UNDEFINED`, `REAL_TIME`, `COMPRESSED_TIME`, `MIXED`.
- `ReportLevel` enum: `LARGEINFO`, `INFO`, `WARNING`, `SEVERE`, `FATAL`; includes
  `meets(threshold)` helper for filter comparisons.
- `TimeEvent` record: engine-internal transport quantum wrapping `Event`, `Topic<?>`,
  originating `Gateway`, `beginTstamp`, and `readTstamp`.
- Initial repository scaffolding.
- Dual-licensing files: `LICENSE-AGPL-3.0`, `LICENSE-COMMERCIAL.txt`.
- Contribution policy with DCO sign-off (`CLA.md`).
- Security disclosure policy (`SECURITY.md`).
- Commercial licensing description (`COMMERCIAL.md`).
- Third-party attribution file (`NOTICE`).
- GitHub Actions workflow enforcing DCO sign-off on pull requests.
