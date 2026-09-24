# The Inventzia Pulse ecosystem

Pulse provides typed events, an event-driven engine for replay, simulation and real-time
applications, and desktop tools for inspecting recorded runs. It ships as three independently
installable components; use the ones you need.

| Package | Purpose | Choose it when… |
| --- | --- | --- |
| [`pulse-data`](https://pypi.org/project/pulse-data/) | Shared typed events, schemas and serialization for Python and Java | You need Pulse data types or want to define extensions |
| [`pulse-beacon`](https://pypi.org/project/pulse-beacon/) | Event-driven execution with Python/Java interoperability and run recording | You want to build and run an application |
| [`pulse-viewers`](https://pypi.org/project/pulse-viewers/) | Desktop tools for browsing runs and inspecting recorded events | You want to examine or follow a recording |

**How they relate.** Beacon depends on Data; Viewers reads Beacon's recorded files and can run
independently, including on another machine. That is why Data and Beacon release together on one
version, while Viewers versions on its own.

## Install what you need

```bash
# Build and run applications from Python; installs pulse-data too.
# Requires Java 17+.
pip install "pulse-beacon[jpype]"

# Inspect recordings on a desktop; no Java required.
pip install pulse-viewers
pulse-events-viewer

# Use only the shared event types and serialization.
pip install pulse-data
```

## Quickstart: run something, then look at what it did

This produces a real run and opens it in the viewer. It takes about four minutes, most of which is
the run itself producing events you can watch arrive.

### 1. Install

```bash
pip install "pulse-beacon[jpype]" pulse-viewers
```

Beacon needs a **JDK 17 or newer** on the machine that runs the engine. The viewer needs neither Java
nor Beacon — if you only want to look at recordings someone else produced, `pip install pulse-viewers`
is the whole story.

### 2. Choose where runs are written

Runs configured for recording — including this example — write themselves into a standardized
directory tree, so they can be found later rather than remembered. Recording is opt-in: an
application gets these artifacts by wiring `RunRecording` into its launcher, not automatically.

```
$PULSE_OUTPUT/<tier>/<app>/<runId>/
    run.json        manifest: mode, window, provenance, status and counts
    events.jsonl    the event recording
    console.log     that run's own log
```

`tier` is `historical` for deterministic replays and `live` for real-time runs.

```bash
export PULSE_OUTPUT=~/PulseOut          # Windows: set PULSE_OUTPUT=C:\PulseOut
```

### 3. Run an example that takes its time

`RealTimeEchoExample` is built to be watched rather than inspected afterwards: a 5-second heartbeat,
an 18-second heartbeat, an actor echoing the slow beat back into the stream as a derived event, and
the engine's own lifecycle recorded alongside the data. It runs for three minutes by default.

```bash
pulse-echo-example                  # 180 s, into $PULSE_OUTPUT
pulse-echo-example 600              # ten minutes, if you want longer to explore
pulse-echo-example 180 ~/PulseOut   # or name the output root outright
```

It prints where it is writing as soon as it starts:

```
Recording into /home/you/PulseOut
Running for 180s — open it in pulse-events-viewer while it goes; a run still being written is followed live.

run output: /home/you/PulseOut/live/RealTimeEchoExample/20260923T081631Z-381399
```

### 4. Watch it while it runs

In another terminal:

```bash
pulse-events-viewer
```

The run browser lists what it finds under the output root. Point it at your folder with **Change
output folder…** if it opens somewhere else — it remembers the choice. The run you just started
appears immediately, marked `running`; open it and the viewer **follows it live**, taking up events
as they are written and stopping by itself when the recorder finishes.

You will see the two heartbeats drifting in and out of phase (5 and 18 share only 90, so they
coincide twice in three minutes), the echo arriving stamped at its triggering beat's time, and
`EngineStatus` events carrying the engine's and each gateway's lifecycle — all in one stream,
coloured by type.

### 5. Read the verdict, not just the data

Each run carries a health verdict built from its manifest, and the viewer shows it rather than
leaving you to infer it:

- The **engine outcome** (`runStatus`) and the **recording outcome** (`recordingStatus`) are separate
  facts, because either can fail while the other succeeds — a run can complete cleanly while its
  recorder failed, and vice versa.
- The counts always balance: `observed = events + overflow + serializationErrors + abandoned`. A
  recording that lost events says so, and is labelled **partial** rather than shown as complete.

A run that crashed, was cut short, or recorded only some of what happened is exactly the run you
open a viewer to look at, so none of those states are hidden.

## Where to go next

- **pulse-data** — defining your own event types, the schema generators, and the cross-language type
  registry: [repository](https://github.com/inventzia-sci-tech/pulse-data)
- **pulse-beacon** — the engine, operating modes, the Python↔Java bridge, and run recording:
  [repository](https://github.com/inventzia-sci-tech/pulse-beacon) ·
  [run output layout](https://github.com/inventzia-sci-tech/pulse-beacon/blob/main/docs/pulse-output.md)
- **pulse-viewers** — the run browser, live following, and the recording contract:
  [repository](https://github.com/inventzia-sci-tech/pulse-viewers) ·
  [viewer design](https://github.com/inventzia-sci-tech/pulse-viewers/blob/main/docs/viewer.md)

## Licensing

All three are dual-licensed: the GNU Affero General Public License v3.0, or a commercial license from
Inventzia Science and Technology Ltd. See each repository's `LICENSE-AGPL-3.0` and `COMMERCIAL.md`.
