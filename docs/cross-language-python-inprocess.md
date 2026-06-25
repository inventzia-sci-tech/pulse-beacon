# Cross-language Python components, in-process (historical replay)

Status: **living document** — drafted as a spec, kept current as tasks are completed. Scope: run
pulse-beacon **components written in Python** — both
**actors** (consumers/strategies) and **gateways** (external boundaries: sources and sinks) —
in-process with the **Java** engine, under **compressed-time (historical) replay**. Real-time and
ZMQ out-of-process transport are out of scope here (later spec).

The forcing function (milestone 1) re-creates `HistoricRunExample` with the Printer/Echo consumers
**and** at least one external feed implemented in Python, proving both roles end-to-end in one run.

The design supports **both embedding directions** behind one abstraction:

- **Python-host** — a Python process boots a JVM via **JPype**. Launch/debug from PyCharm. First
  milestone (complex client debugging happens here first).
- **Java-host** — a JVM process boots a CPython interpreter via **JEP**. Launch/debug from the Java
  IDE.

Everything from the Python component down to and including the streamer loop is written **once** and
runs unchanged under either launcher (see §2).

---

## 1. Invariants and boundaries

From the existing engine and gateways — the reason the design looks the way it does. These are the
constraints the cross-language layer must not break:

1. **The engine is authoritative.** It owns the clock (`TimeMachine`), routing, and event
   ordering. A cross-language component is a guest; it never drives time on its own.
2. **Consume determinism = synchronous dispatch.** `AbstractEngine.dispatchTimeEvent` calls
   `actor.onEvent(...)` **synchronously on the dispatch thread**, then calls `timeMachine.done(te)`
   to permit clock advance. So a cross-language consumer whose `onEvent` blocks until Python has
   processed the event keeps replay deterministic for free (the old `setDoneConsumingOneDatum` is
   subsumed).
3. **Produce determinism = write permits.** A clock-driving gateway (`setDriveClock(true)`, a
   `Runnable`) emits by calling `downstream.onEvent(topic, payload)`, and **in compressed time that
   call blocks on the TimeMachine's per-gateway write permit until the previous event is consumed**
   (see `HeartBeatGateway.run`). That blocking paces the source so all clock-drivers merge in
   event-time order. A cross-language source inherits this unchanged. Exhausting the source →
   `disconnect()` tells the engine the clock source is done.
4. **Payloads are immutable; JSON is the canonical form.** Every `Datum` has identical Java/Python
   generated types and a canonical JSON encoding (`DatumCodec` / pulse-data Python models). The
   boundary carries JSON; each side reconstitutes a native object.
5. **Lifecycle is a transport concern, not a datum.** Start/stop cross the boundary as a
   transport-level `CrossLanguageEvent.Kind` (`DATA` / `START` / `STOP` / `END`), *not* as an
   engine `EngineCommand`: lifecycle signalling belongs to the bridge, and pulse-data's
   codec/registry know nothing of pulse-beacon's command types. (Still far better than the old
   magic-string `StringDatum("OnStop")` on a side-channel topic.) `START`/`STOP` carry the
   simulation time; `END` terminates a streamer loop.

---

## 2. The layered abstraction

Only the bottom layer is direction-specific. Everything above — including the streamer loop — is
write-once Python.

```
  ┌──────────────────────────────────────────────────────────────────┐
  │ (A) Component API — pure Python, no JVM imports                    │
  │     BeaconActor:   on_start / on_event / on_stop  (+ publish)      │
  │     BeaconGateway: produce() [source]  and/or  on_event() [sink]   │
  ├──────────────────────────────────────────────────────────────────┤
  │ (B) Channel + dispatch — pure Python, direction-agnostic           │
  │     BeaconChannel.publish(topic, datum) -> serialize, hand to Java │
  │     dispatch(component, ch, topic_name, json) -> decode + route    │
  ├──────────────────────────────────────────────────────────────────┤
  │ (C) CrossLanguageStreamer — Python, used UNCHANGED both directions │
  │     run_consume(): ev = jp.takeNext(); dispatch(...); jp.ackDone() │
  │     run_produce(): for t,d in produce(): jp.offerNext(t, json)     │
  ├──────────────────────────────────────────────────────────────────┤
  │ (D) Java side — two components                                     │
  │     CrossLanguageActor   extends AbstractActor   (consume + pub)   │
  │     CrossLanguageGateway extends AbstractGateway (source + sink)   │
  ├──────────────────────────────────────────────────────────────────┤
  │ (E) Host bootstrap + launcher — THE ONLY direction-specific part   │
  │     jpype_host: start JVM, build graph, run streamer on Py thread  │
  │     jep_host:   Java builds graph + JEP interp on the streamer     │
  │                 thread, then calls CrossLanguageStreamer.run_*()    │
  └──────────────────────────────────────────────────────────────────┘
```

The `CrossLanguageStreamer` loops are Python and only *call* the Java component
(`takeNext`/`ackDone`/`offerNext`/`finish`) and the Python component (`on_event`/`produce`). They
are identical in both directions; under JPype they run on a Python-owned thread, under JEP on the
JEP thread Java created. Direction-specific surface = the launcher bootstrap only.

---

## 3. The boundary contract

Every event crossing the JNI boundary, either direction, is the pair:

```
(topic_name: String, datum_json: String)   // CrossLanguageEvent
```

- **Inbound (engine → Python):** Java encodes the `Datum` with `DatumCodec.instance().toJson(...)`;
  Python decodes to a native model.
- **Outbound (Python → engine):** Python encodes; Java decodes with `fromJson(json, type)` and
  emits onto the bus.

Decoding on the Python side needs `TYPE_ID → model class`. The codec emits the `TYPE_ID` as a field
in the JSON envelope, and pulse-data carries a generated `TYPE_ID → class` registry in both
languages, so decode is self-describing on either side — the modern form of the Old
`DatumIdsPubSub` / `CepBaseDatumFactory`. See **D2** (§9) for the rationale and what this adds to
`DatumCodec`.

JSON-at-boundary even in-process keeps the Python component 100% native (no Java handles in business
logic), unit-testable without a JVM, and gives the future ZMQ path the identical Python model. Cost
is a serialize/parse per event — optimizable later without touching component code.

---

## 4. The Java side — two components

### 4a. `...core.crosslanguage.CrossLanguageActor extends AbstractActor`

Consumer/strategy. Engine pushes events in; Python publishes derived events back out.

```java
final class CrossLanguageActor extends AbstractActor {
    private final SynchronousQueue<CrossLanguageEvent> inbound = new SynchronousQueue<>();
    private final Semaphore done = new Semaphore(0);

    // engine dispatch thread, synchronous:
    @Override public <P extends Datum> void onEvent(Topic<P> topic, P payload) {
        inbound.put(new CrossLanguageEvent(topic.name(), DatumCodec.instance().toJson(payload)));
        done.acquire();                       // BLOCK until Python acks -> clock can't advance
    }
    public CrossLanguageEvent takeNext() throws InterruptedException { return inbound.take(); }
    public void ackDone() { done.release(); }

    // Python -> bus (actor publishing derived events):
    public void publish(String topicName, String json) {
        Topic<?> t = topics.get(topicName);
        publish(t, DatumCodec.instance().fromJson(json, t.payloadType()));
    }

    // lifecycle delivered in-band on the consume channel as EngineCommand JSON:
    @Override protected void onStartUp(long t) { inbound.put(startCommand(t)); done.acquire(); }
    @Override protected void onShutDown(long t){ inbound.put(stopCommand(t));  done.acquire();
                                                 inbound.put(STOP); }   // ends run_consume loop
}
```

### 4b. `...core.crosslanguage.CrossLanguageGateway extends AbstractGateway`

External boundary. **Source** role (clock-driving feed) and **sink** role (run → external) in one
component, mirroring the old bidirectional `PythonGateway`. Source role sets `setDriveClock(true)`.

```java
final class CrossLanguageGateway extends AbstractGateway {

    // --- SOURCE: Python -> engine, permit-paced (mirror of HeartBeatGateway.run) ---
    private final SynchronousQueue<CrossLanguageEvent> outbound = new SynchronousQueue<>();
    private final Semaphore accepted = new Semaphore(0);

    // Python streamer thread: "here is my next datum" — blocks until emitted through the permit.
    public void offerNext(String topicName, String json) {
        outbound.put(new CrossLanguageEvent(topicName, json));
        accepted.acquire();
    }
    public void finish() { outbound.put(END); }    // Python source exhausted

    @Override public void run() {                  // gateway thread == clock driver
        initialize(); connect(); setStatus(STARTED);
        try {
            while (connected()) {
                CrossLanguageEvent ev = outbound.take();
                if (ev == END) break;
                Topic<?> t = topics.get(ev.topicName());
                Gateway downstream = subscriberForKey(t, keyOf(ev));
                if (downstream != null) {
                    // BLOCKS on the write permit until the prior event is consumed:
                    downstream.onEvent(t, DatumCodec.instance().fromJson(ev.json(), t.payloadType()));
                }
                accepted.release();                // let Python produce the next
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        disconnect(); setStatus(STOPPED);          // signal this clock source is done
    }

    // --- SINK: engine -> Python (same handshake as CrossLanguageActor) ---
    private final SynchronousQueue<CrossLanguageEvent> inbound = new SynchronousQueue<>();
    private final Semaphore done = new Semaphore(0);
    @Override public <P extends Datum> void onEvent(Topic<P> topic, P payload) {
        inbound.put(new CrossLanguageEvent(topic.name(), DatumCodec.instance().toJson(payload)));
        done.acquire();
    }
    public CrossLanguageEvent takeNext() throws InterruptedException { return inbound.take(); }
    public void ackDone() { done.release(); }
}
```

Role matrix:

| Python component | Java counterpart | Threads/handshakes used |
|---|---|---|
| `BeaconActor` | `CrossLanguageActor` | consume (inbound) + `publish` |
| `BeaconGateway` source-only | `CrossLanguageGateway` (`driveClock=true`) | produce (outbound, permit-paced) |
| `BeaconGateway` sink-only | `CrossLanguageGateway` (`driveClock=false`) | consume (inbound) |
| `BeaconGateway` source+sink | `CrossLanguageGateway` (`driveClock=true`) | produce **and** consume (two streamer threads) |

> **Implemented (step 2).** The sketches above are illustrative; the built API is:
> `CrossLanguageEvent` is a `record(kind, topicName, taggedJson, time)` with `Kind {DATA, START,
> STOP, END}` (the codec methods are `toTaggedJson`/`fromTaggedJson`). `CrossLanguageActor` exposes
> `takeNext()` / `ackDone()` (consume) and `publishTagged(topicName, taggedJson)` (publish), with
> `onStartUp`/`onShutDown` handed across as `START`/`STOP` then `END`. `CrossLanguageGateway` exposes
> `offerNext(topicName, taggedJson)` / `finish()` (source) and `takeNext()` / `ackDone()` (sink);
> it `setDriveClock(true)` by default and routes by the decoded datum's `getDatumKey()` (D4). Both
> register publishable/produced topics via `withTopic(...)`.

---

## 5. The Python side — beacon-core Python extension (written once)

The home for building consumers **and** gateways in Python. Mirrors the Java package
(`com.inventzia.pulse.beacon.core`) and the pulse-data Python layout:

```
core/python/inventzia/pulse/beacon/core/
  actor.py        # BeaconActor: on_start / on_event / on_stop  (+ publish via channel)
  gateway.py      # BeaconGateway: produce() [source]  and/or  on_event() [sink]
  channel.py      # BeaconChannel: publish(topic, datum)  (mirror of the actor's bound Pub bus)
  dispatch.py     # decode JSON -> native model; route EngineCommand->on_start/on_stop, else on_event
  crosslanguage/
    cross_language_streamer.py  # CrossLanguageStreamer: run_consume() / run_produce()
    jpype_host.py               # Python-host bootstrap: start JVM, build graph, launch streamers
    jep_host.py                 # Java-host entry: invoked by JEP to wire component + run streamers
  examples/
    print_consumer.py    # PrintConsumer(BeaconActor)
    echo_consumer.py      # EchoConsumer(BeaconActor)
    message_feed_gateway.py  # source BeaconGateway: yields TextMessage events in time order
```

`cross_language_streamer.py` — both loops, same code both directions (as built):

```python
class CrossLanguageStreamer:
    def __init__(self, endpoint, component, channel=None):
        self._endpoint, self._component = endpoint, component
        if channel is not None and hasattr(component, "bind"):
            component.bind(channel)              # so on_event handlers can publish

    def run_consume(self):                       # engine -> Python (actor, or gateway-sink)
        while True:
            ev = self._endpoint.takeNext()       # blocks; a CrossLanguageEvent
            if event_kind(ev) == "END":
                break
            try:
                dispatch_consume(self._component, ev)   # decode tagged JSON + route by Kind
            finally:
                self._endpoint.ackDone()         # release the engine's dispatch thread

    def run_produce(self):                       # Python -> engine (gateway-source)
        for topic_name, datum in self._component.produce():
            self._endpoint.offerNext(topic_name, to_tagged_json(datum))  # blocks until accepted
        self._endpoint.finish()
```

`dispatch_consume(component, event)` reads the event's `Kind`: `DATA` → decode the tagged JSON to a
native datum and call `on_event(topic_name, datum)`; `START`/`STOP` → the lifecycle hooks; `END` is
handled by the loop above. The channel is *bound* to the component (mirroring Java's
`AbstractActor.bind`) rather than passed into `dispatch`, so `publish` reads naturally inside
`on_event`. Topics cross as names (strings); the Java side owns the `Topic` objects and types.

A bidirectional gateway launches `run_produce` and `run_consume` on two threads — the modern
equivalent of the old `PythonGateway`'s `run_outgoing_data` / `run_incoming_data` pair.

---

## 6. The two directions, concretely (using a Python source gateway)

### 6a. Python-host (JPype) — milestone 1

```
PyCharm runs historic_run_jpype.py
  └─ jpype.startJVM(classpath = pulse-beacon + pulse-data jars)
  └─ build engine; register Python actors (CrossLanguageActor) and the Python
     feed (CrossLanguageGateway, driveClock=true)
  └─ start engine + gateway threads (JVM)
  └─ launch streamers on Python threads:
        actor:  CrossLanguageStreamer(jActor, printer, ch).run_consume()
        feed:   CrossLanguageStreamer(jGateway, feed, ch).run_produce()

  produce (Py thread):  feed.produce() -> jGateway.offerNext(topic, json)
                          └─ Java gw.run(): downstream.onEvent(...) BLOCKS on permit
                          └─ accepted.release() -> Python yields next
  consume (Py thread):  jActor.takeNext() ◄── engine onEvent (blocks) ; on_event(datum); ackDone()
```

End-to-end debuggable in PyCharm: breakpoints in both `produce()` and `on_event()`.

### 6b. Java-host (JEP)

`HistoricRunJepExample.main` builds the same graph, creates a JEP interpreter on each streamer
thread, imports `jep_host`, wires the same Python components, and calls
`CrossLanguageStreamer.run_consume()/run_produce()`. Same Java components, same streamer, same
Python components — only the bootstrap differs.

---

## 7. Threading & determinism notes

- **One thread per loop.** Each consume/produce loop is single-threaded, so a component sees events
  in engine order. A bidirectional gateway uses two threads (one per loop) — independent, each with
  its own handshake (`done` vs `accepted`).
- **Consume handshake** preserves invariant 1.2 (engine dispatch thread blocks until Python acks).
  **Produce handshake** preserves invariant 1.3 (the gateway's `downstream.onEvent` blocks on the
  write permit; `offerNext` blocks Python until that returns).
- **JEP thread affinity.** A JEP interpreter is bound to its creating thread; each streamer thread
  creates and uses its own. The design keeps all Python calls on the owning streamer thread.
- **JPype + GIL.** Streamers run as Python threads making blocking JNI calls; JPype manages JVM
  attach. We deliberately do **not** let JVM threads call Python directly (`@JImplements`) — the
  `SynchronousQueue` handoffs keep engine/gateway threads in Java and Python on streamer threads.
- **Shutdown.** Consumer: `onShutDown` delivers a stop command then a STOP sentinel, ending
  `run_consume`. Source: Python `produce()` exhaustion → `finish()` → gateway `disconnect()`. The
  launcher joins engine + gateway threads and (Python-host) shuts the JVM down.

---

## 8. Deliverables for milestone 1 (Python-host historical; consumers + source gateway)

**STATUS: DONE (2026-06-16).** All six items built; the launcher runs to `COMPLETE` and the Python
printer receives exactly the 10-event, event-time-ordered merge of the all-Java `HistoricRunExample`
(`PARITY OK`). The Python `EchoConsumer` publishes back through the channel to the Java sink.

Run recipe (from `New/`, in the shared `pulse` env — its bundled JDK sets `JAVA_HOME`, so none is
exported manually):

```bash
export PYTHONPATH="pulse-data/datum/python:pulse-data/schemas/schemas_py:pulse-beacon/core/python"
conda run -n pulse python pulse-beacon/core/python/inventzia/pulse/beacon/core/examples/historic_run_jpype.py
```

Jars are staged into `core/java/jars/` (build artifacts) from `core/java/` with:
`conda run -n pulse mvn -o package -DskipTests` → `conda run -n pulse mvn -o dependency:copy-dependencies -DoutputDirectory=jars -DincludeScope=runtime` → `cp target/pulse-beacon-core-*.jar jars/`.

Two gotchas learned, now handled:
- **JPype string conversion.** Java `String` returns are *not* Python `str` by default, which breaks
  `json.loads` on the tagged JSON. The bootstrap starts the JVM with `convertStrings=True`.
- **Streamer resilience.** A raising consume handler must not kill the streamer thread — the engine's
  dispatch thread is blocked awaiting its ack, so a dead streamer deadlocks the run. `run_consume`
  catches per-event, logs, acks, and continues.

1. Java `core/crosslanguage/`: `CrossLanguageActor`, `CrossLanguageGateway`, `CrossLanguageEvent`,
   a cross-language exception.
2. pulse-data: generated `TYPE_ID → class` registry in Java **and** Python, and `DatumCodec`
   emitting the `TYPE_ID` envelope field + a self-describing `fromJson(json)` (see D2).
3. pulse-beacon Python `core/python/inventzia/pulse/beacon/core/`: `actor.py`, `gateway.py`,
   `channel.py`, `dispatch.py`, `crosslanguage/cross_language_streamer.py`,
   `crosslanguage/jpype_host.py`.
4. Python components: `print_consumer.py`, `echo_consumer.py`, `message_feed_gateway.py` (source).
5. Launcher `historic_run_jpype.py`: re-creates `HistoricRunExample` with the Python feed driving
   the clock and Python consumers receiving — asserts the consumers saw the same events as the Java
   run.
6. Conda env adding `jpype1` (`jep` deferred to the Java-host milestone).

Java-host milestone (next): `HistoricRunJepExample.java` + `crosslanguage/jep_host.py`, reusing 1–4
unchanged. Sink-only gateway example can follow once a Java→external example target exists.

---

## 9. Decisions

- **D1 — Python package home. RESOLVED.** `core/python/inventzia/pulse/beacon/core/` inside
  pulse-beacon, mirroring the Java package and the pulse-data Python layout. No separate repo.

- **D2 — Self-describing decode via a `TYPE_ID` registry. RESOLVED: build it now.**
  This is the modern form of the Old `DatumIdsPubSub` + `CepBaseDatumFactory`. In the Old framework
  each datum carried a numeric `datumIntId` and `I_DatumFactory.create(intId)` switched on it to
  construct the right `A_Datum` for deserialization. The new world already has the discriminator —
  each generated type exposes `TYPE_ID` (the schema `$id` / FQN). What is missing is the *lookup*:
  - **Java:** a generated `TYPE_ID → Class<? extends Datum>` map so `DatumCodec` gains a
    self-describing `Datum fromJson(String)` (read the `TYPE_ID` field, look up the class) alongside
    today's type-directed `fromJson(json, Class)`.
  - **Python:** the mirror `TYPE_ID → model class` map.
  - For this to work the codec must **emit the `TYPE_ID` as a field in the JSON envelope** (today it
    is a static constant Jackson does not serialise). That envelope is what makes the boundary
    self-describing.

  Why build it now rather than interim-keying off the topic's declared payload type: (a) the ZMQ
  transport will *require* it (a socket carries mixed types with no ambient topic→type map), and (b)
  it removes a later rewrite. Both registries are tiny and generated from the same YAML, so the cost
  is low. *(Closes the deferred DatumCodec TYPE_ID-registry TODO; the cross-language layer is its
  first consumer, ZMQ the second.)*

- **D3 — `BeaconChannel` name. RESOLVED for now.** Keep `BeaconChannel` (the Python mirror of the
  actor's bound `Pub` bus). Revisit only if a better name emerges in use.

- **D4 — Gateway key extraction. RESOLVED.** The `CrossLanguageGateway` source path decodes the
  `Datum` first and calls `getDatumKey()` for `subscriberForKey` — single source of truth, no key
  duplicated into `CrossLanguageEvent`.

- **D5 — JVM bootstrap in Python-host. RESOLVED: Maven-produced versioned jar set.**
  Same pattern as the Old `AlgoTradingJars/20231217/AlgoTradingJavaCepBase.jar` + `Libs_Os/`
  dependency jars on the classpath — but assembled by the existing Maven build instead of a
  hand-rolled compilation suite. The build stages a release jar set (pulse-beacon + pulse-data +
  their runtime deps: Jackson, jspecify) into a versioned `core/java/jars/<version>/` directory
  (via `dependency:copy-dependencies`, or a single shaded uber-jar). `jpype_host.py` globs that
  directory onto the classpath at `startJVM`. No separate compilation system to maintain; Maven
  remains the single source of build truth, the jars dir is its release output.
```
