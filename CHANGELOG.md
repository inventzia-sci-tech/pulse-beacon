# Changelog

All notable changes to pulse-beacon are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.3.0] - 2026-09-24

### Added

- **Run recording (Stage A): a run can write itself to a standardized output layout.** Recording is
  opt-in — a launcher wires in `RunRecording`, existing applications are unaffected — and a run so
  configured produces `$PULSE_OUTPUT/{historical|live}/<app>/<runId>/` containing `run.json`
  (manifest), `events.jsonl` (the recording) and `console.log` (that run's own log). The engine gained only
  observation hooks — `RunListener` / `RunOutcome`, and a `ReportTap` on `Slf4jReporter` so a per-run
  console can be captured without compiling the engine against Logback. Everything about
  directories, manifests and appenders lives launcher-side in
  `com.inventzia.pulse.beacon.core.run` (`RunLayout`, `RunConsole`, `RunRecording`) with
  `EventRecorderGateway` under `gateway.recording`.

  The manifest keeps the **engine outcome (`runStatus`) and the recording outcome
  (`recordingStatus`) separate**, because either can fail while the other succeeds, and carries
  counts that always balance (`observed = events + overflow + serializationErrors + abandoned`) so a
  lossy or failed recording can never be mistaken for a complete one. Stage A's limits —
  selected-routes capture, recorder-local sequence — are declared in the manifest's `recording`
  descriptor rather than left implicit. The all-routes engine tap with a global dispatch sequence is
  Stage B.

- **A run's lifecycle is published as events.** `AbstractGateway` gained a `StatusListener` seam on
  its single `setStatus` choke point, and `AbstractEngine.publishStatusEvents(...)` relays its own
  and its gateways' transitions onto `engine.status` as `EngineStatus` events;
  `RunRecording.recordStatus(...)` wires recorder and publisher in one call.

  Status is delivered **directly to the topic's subscriber rather than through the TimeMachine**,
  deliberately: the first transitions happen before the dispatch barrier opens and the last after
  the dispatch loop has returned, so a queued status event would lose both ends of the run and keep
  only the quiet middle. It also keeps a wall-clock lifecycle fact from colliding with the causality
  check during a compressed-time replay.

- **`RealTimeEchoExample`**, a long real-time run built to be watched: 5-second and 18-second
  heartbeats, an `EchoConsumer` publishing a derived event back into the stream, and its own
  lifecycle recorded alongside. Runnable from an installed wheel as the console script
  **`pulse-echo-example [seconds] [outputRoot]`** (needs the `[jpype]` extra and a JDK 17+), so the
  documented quickstart needs no classpath incantation.

### Fixed

- **A busy recording is no longer invisible until its buffer fills.** The writer flushed only when
  its queue went idle, which a steady stream never reaches, so events sat buffered until 8 KB had
  accumulated — dozens of events of latency for anything following the recording. Buffered events
  are now also flushed every `DEFAULT_FLUSH_INTERVAL_MILLIS` (250 ms), tunable with
  `setFlushIntervalMillis` (0 flushes per record). Idle behaviour is unchanged.

- **A wedged recorder can no longer hang a run.** `RunRecording` bounds the drain: if the writer
  ignores its stop request the join gives up, interrupts, and finalization proceeds, recording
  `recordingStatus: "unknown"` rather than guessing. A writer that does stop on interruption is
  reported as `"interrupted"` — "I don't know what the recording contains" and "it was cut short"
  are different facts.

- **A recording that fails to start is no longer silent.** If the run directory cannot be prepared
  (unwritable output root, full disk) the run still continues — an observer must never take down
  what it observes — but it now logs the reason and the resolved root, and exposes `isRecording()`,
  `startupFailure()` and `requireRecording()`. Whether an unrecorded run is acceptable is the
  launcher's call, so the library makes the failure loud and leaves the verdict to the caller.

### Known limitations — recording (Stage A)

Recording ships deliberately incomplete, and a reader must not assume otherwise. Both limits are
declared in every `run.json` under `recording`, so a consumer can detect them rather than infer them:

- **Selected routes, not the whole engine.** The recorder is a *subscriber sink*, and Beacon routing
  is one subscriber per `(topic, key)`, so it records only routes it was explicitly registered on and
  only routes that have no other sink. A recording is therefore a chosen subset of the run, never
  automatically the complete dispatch stream. Declared as `"capture": "selected-routes"`.
- **Recorder-local sequence numbers.** `seq` is the order *this recorder* received events on *its*
  routes — not the engine's global dispatch sequence. Sequence numbers are comparable within one
  recording and must not be read as positions in the engine's overall ordering. Declared as
  `"sequence": "recorder-local"`.

Both are lifted by the engine event tap (Stage B), which is not in this release.

### Changed

- **PyPI metadata repositioned as general-purpose.** Dropped the finance-specific classifiers for
  `Developers` / `Information Technology` / `Science/Research` and
  `Topic :: Software Development :: Libraries` (Python Modules + Application Frameworks). Reworded the
  summary to lead with capabilities, and set capability-focused keywords (deterministic-replay,
  simulation, event-processing, typed-events, cross-language). Domain/finance positioning stays on
  the domain adapters.

## [0.2.3] - 2026-09-16

### Added

- **Quickstart in the README** (pip-first onboarding): `pip install`, the JDK 17+ requirement, and
  one runnable bundled example near the top of the page.

### Fixed

- **`release-build.sh` guards against stale-build wheel contamination.** It now removes each
  package's `build/` before building and verifies every packaged `.py` exists in `src/`, so a module
  deleted from source but left in `build/lib` (as an obsolete pulse-data `schemas/registry.py` once
  was, shipping in the 0.2.2 wheel) cannot be zipped into a release wheel.
- **Runtime jar embeds the correct pulse-data Maven descriptor.** The shaded runtime jar carried a
  stale pulse-data `pom.xml`/`pom.properties` (`0.2.0-SNAPSHOT`, including in the 0.2.2 release)
  because `mvn install` without `clean` never regenerates
  `target/classes/META-INF/maven/.../pom.properties`. `release-build.sh` now clean-installs pulse-data
  before shading, `build-runtime-jar.sh` asserts the embedded pulse-data descriptor matches the
  resolved version, and CI uses `clean install`. Classes and behaviour were always correct; only the
  embedded Maven metadata was stale.

## [0.2.2] - 2026-09-15

### Fixed

- **First published release.** 0.2.0 and 0.2.1 were tagged but never published (the 0.2.1 tag
  landed before pulse-data's provider was regenerated). 0.2.2 is the first release published to
  PyPI. No functional change from 0.2.1.

## [0.2.1] - 2026-09-15

### Fixed

- **PyPI project page.** README relative links are now absolute GitHub URLs (relative links do not
  resolve on PyPI), so the extension example and cross-language docs are reachable from the PyPI
  page; `[project.urls]` gains `Repository` and `Changelog`. Documentation/metadata only; no code
  change from 0.2.0.

## [0.2.0] - 2026-09-13

### Added (cross-language type-universe gate, SPI Phase 3)

- **The JPype bridge fails fast on a datum-type mismatch.** `start_jvm()` now verifies, once
  the JVM is up and before any event flows, that the Python and Java composite datum-type
  fingerprints agree (`verify_type_universe()`); a mismatch raises `TypeUniverseMismatch` with a
  per-type diff (which types are only on one side or differ), and an unverifiable side (a
  provider without a manifest) is fail-closed. So a producer cannot emit an extension datum the
  receiving runtime cannot decode. Opt out with `start_jvm(verify=False)`.
- **`RunInfo` records the datum-type universe.** `RunInfo` gains `typeFingerprint` and
  `providerIds`, so a run is traceable to the exact set of types it could route (auditability /
  reproducibility). `AbstractEngine.runInfo()` populates them from the composite registry.

### Fixed

- **`start_jvm()` no longer fails open when a JVM is already running.** Previously it returned
  immediately on `isJVMStarted()`, so verification was skipped and a mismatch present on JVM reuse
  (retries after a failed verify, notebooks, host applications that already booted the JVM) passed
  silently, and any requested `extra_classpath` / `jars_dir` was dropped without warning. The reuse
  path now still runs `verify_type_universe()` (unless `verify=False`), and raises `RuntimeError`
  if asked to add jars that are not already on the running JVM's classpath (a running JVM's
  classpath cannot be changed) instead of ignoring them.

### Added (run observer)

- `RunInfo(OperatingMode mode, long startTime, long endTime)` + public
  `AbstractEngine.runInfo()` — the sanctioned, read-only way for a run orchestrator to
  record a run's mode and window (logging, telemetry, run manifests, mode-selection
  tests) without reaching into the protected `operatingMode()` accessor. Actors hold
  only a `Pub`, so a strategy still cannot obtain the mode and branch on it (production
  parity). `operatingMode()` stays `protected` for gateways that legitimately adapt to
  the mode (e.g. a broker gateway refusing to trade during a replay); its javadoc now
  states the parity rationale explicitly.

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

### Packaging & distribution

- **Installable `src/` layout + `pyproject.toml`.** `core/python/inventzia` → `src/inventzia`,
  tests → `tests/`; public `inventzia.pulse.*` imports throughout (dropped the repo-root-prefixed
  imports and the conftest `sys.path` shim). PEP 420 namespace with deliberate exports, so
  `from inventzia.pulse.beacon.core import BeaconActor` resolves. Pins `pulse-data==<version>` (the
  runtime jar embeds Java pulse-data, so the installed Python pulse-data must match) and offers
  opt-in `[jpype]` / `[jep]` extras.
- **Bundled JPype runtime jar.** A shaded `pulse-beacon-core-runtime.jar` (Beacon + its Java
  dependencies, with a generated `META-INF/THIRD-PARTY.txt` license inventory and an aggregated
  `NOTICE`) is staged into the wheel; `jpype_host` finds it via `importlib.resources` (falling back
  to the source-tree staged jars). `pip install pulse-beacon[jpype]` starts the JVM with no external
  jars, `PYTHONPATH`, or Maven. An in-tree build backend (`_build_backend.py`) fails a wheel build
  that would omit the jar.
- **JEP resolves from Maven Central** (`black.ninia:jep:4.2.2`) — the manual `install-file` is gone;
  the runtime jep is pinned to the same version.
- **Example fixtures shipped as package data** (discovered via `importlib.resources`), so the
  documented examples run from an installed wheel; the historic run no longer needs the source tree.
- **Cross-language runs fail fast** on a dead producer (streamer `finish()` in a `finally` plus a
  supervised join) instead of hanging the full timeout.
- **Integration tests run against the installed artifact.** They resolve the Beacon classpath
  (bundled jar or staged jars) and are marked `integration` / `jep`; a missing prerequisite skips
  locally but *fails* under `PULSE_REQUIRE_INTEGRATION` (release CI must test, not skip).
- **Release tooling**: `build-runtime-jar.sh`, `dist-smoke-test.sh`, `check-versions.sh` (dev/release
  version gate), `release-build.sh`, and `RELEASING.md` (the tag ceremony). `pom.xml` gains release
  metadata and source/javadoc jars.
- **CI** (`.github/workflows/ci.yml`): `maven` (jep from Central), `dist` (runtime jar + distribution
  smoke test), `integration` (installed-artifact tests, strict), and a tag-triggered `release` job.
- **`VectorValue` end-to-end example + tests** (`vector_value_run_jpype`): a Python VectorValue
  stream through the Java engine, plus cross-language codec parity, the parallel-length validator,
  and immutability tests in both languages.
