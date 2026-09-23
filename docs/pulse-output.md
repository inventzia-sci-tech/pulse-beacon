# Pulse output layout, a design suggestion

## Goal

A standardized place where every Pulse run writes its outputs, organized so a person or the viewer
can find and open a specific run. This is the concrete substrate for the Mission's Auditability and
Reproducibility: each run's provenance, event stream, and logs live in one predictable directory.

## Layout

```
$PULSE_OUTPUT/                                  # the output root (configurable; see below)
  historical/                                   # COMPRESSED_TIME runs (deterministic replay)
    HistoricalBarExample/                       # the client application / run name
      20260918T142530Z-3f9a1c/                  # one run: UTC timestamp + short uuid
        run.json                                # manifest (metadata + status + counts)
        events.jsonl                            # the event recording (EventRecorderGateway)
        console.log                             # full human log for the run (Reporter / SLF4J)
  live/                                         # REAL_TIME runs
    RealTimeBarExample/
      20260918T145012Z-77b2e0/
        run.json
        events.jsonl
        console.log
```

Three tiers under the root: **mode** (`historical` or `live`), then the **application name** (what
the client run calls itself, e.g. `HistoricalBarExample`), then the **run directory**. This makes
"all runs of this app", "all live runs", and "the newest run" trivial to navigate.

- **Mode tier** is derived from the engine's operating mode: `COMPRESSED_TIME -> historical`,
  `REAL_TIME -> live` (a future `MIXED` maps to `live`).
- **Application name** is supplied by the run (the launcher passes it), so the examples become
  `HistoricalBarExample`, `RealTimeBarExample`, and so on.
- **Run directory** is `<UTC timestamp>Z-<short uuid>`, timestamp-first so runs sort
  chronologically, uuid-suffixed so concurrent or same-second runs never collide. The `runId` in the
  recording equals this directory name, tying records back to their folder.

## The manifest: run.json

A small file so a run browser can list runs without parsing each recording, and so a run still in
progress (no trailer yet) is describable:

```json
{
  "runId": "20260918T142530Z-3f9a1c",
  "app": "HistoricalBarExample",
  "mode": "COMPRESSED_TIME",
  "tier": "historical",
  "source": "engine:Engine",
  "createdAt": "2026-09-18T14:25:30Z",
  "endedAt": null,
  "window": { "start": 1283630000000, "end": 1283630007000 },
  "typeFingerprint": "64d6f0...",
  "providerIds": ["com.inventzia.pulse.data"],
  "runStatus": "running",
  "recordingStatus": "recording",
  "counts": null,
  "artifacts": { "manifest": "run.json", "events": "events.jsonl", "console": "console.log" }
}
```

Two independent outcomes, never conflated. `runStatus` is the **engine** run's outcome
(`running` -> `completed` | `failed` | `aborted`); `recordingStatus` is the **recorder's** outcome
(`recording` -> `complete` | `failed` | `interrupted`), taken from the recording trailer. An engine
can fail while its recorder drains cleanly, or finish cleanly while recording fails, so the two are
separate fields. `counts` holds the recording counters (observed, events, overflow,
serializationErrors, abandoned), unchanged from the trailer, published only after the recorder has
closed. The manifest overlaps the recording's header and trailer on purpose: it is the cheap,
always-present, updatable index card; the recording stays the source of truth for the events.

## Artifacts (for now)

- `run.json` (manifest).
- `events.jsonl` (the recording, from the event tap; see viewer.md and event-record.schema.json).
- `console.log` (the full human log for the run).

Later, optionally: `gateways/<name>.jsonl` for other sink outputs, and `inputs/` for parameters and
input references that make a run reproducible.

## Configuration: the output root

Resolution order, one place, documented:

1. an explicit path passed by the launcher,
2. else the `PULSE_OUTPUT` environment variable,
3. else the default `~/.pulse/runs`.

The default is user-global and cross-platform (`Path.home()/".pulse"/"runs"`); set `PULSE_OUTPUT` to
put runs under a project instead. (Open: whether a project-local `./pulse-output` should be the
default instead of user-global.)

## Who creates it, and the shutdown ordering

A small **run context** in pulse-beacon, established at run start once the operating mode is known
(the engine determines mode at `initialize()`): it allocates the `runId`, creates the run directory,
writes the initial `run.json` (`runStatus: running`, `recordingStatus: recording`), and routes the
recorder to `<run-dir>/events.jsonl` and the Reporter/SLF4J console to `<run-dir>/console.log`.

Shutdown is ordered so the manifest never reports optimistic or stale results:

1. the engine finishes; the run context records `runStatus` = `completed` | `failed` | `aborted`;
2. the recorder is stopped and **drained and closed**, producing its trailer (final `counts` and
   `recordingStatus`);
3. only then does the run context finalize the manifest, publishing `recordingStatus` and `counts`
   from the closed recorder.

Final recording counts are published after the recorder closes, not merely when the engine returns.

## Manifest updates: one writer, atomic

The manifest has exactly **one writer**, the run context. Every update is written to a temporary file
in the run directory, flushed, then **atomically replaced** over `run.json` (`os.replace` in Python,
`Files.move` with `ATOMIC_MOVE` in Java). A reader, or a crash mid-write, therefore never sees a
half-written manifest: `run.json` is read as a whole, and there is no partial state.

## Collision-safe, path-safe run directories

- **Fresh directory, no silent reuse.** The mode and app tiers may already exist, but the run
  directory itself is created atomically and must not already exist. A `runId` collision (vanishingly
  rare given the uuid suffix) generates a new id and retries; a caller-supplied id that already exists
  is an error, never a silent overwrite of an existing run.
- **Path-safe components.** The application name is sanitized to a single safe path component
  (letters, digits, `._-`; anything else collapsed to `-`; leading and trailing dots and dashes
  stripped; length bounded; `.`, `..`, and empty become `unnamed`), so it can neither introduce a
  path separator nor traverse out of its tier. The resolved run directory is verified to be under the
  output root before anything is written.

## Per-run log isolation

Each run's console output goes to its own `console.log`, via a **run-scoped log appender** the run
context adds at run start and removes and closes at finalize. Logs from one run therefore never land
in another's file, including concurrent runs in the same process, and the handle is released at run
end rather than leaked. The global logging configuration is untouched; the per-run appender is
additive and scoped to the one run.

## Crash and unknown completion

A crash leaves the last atomically-written manifest, typically `runStatus: running`. A run that is no
longer active but still marked `running` (or an `events.jsonl` with no trailer line) means
**completion unknown**, never success. Readers and the viewer must present it that way (for example
"running / unknown") and must not infer completion from the mere absence of a failure. A stale
manifest cannot assert that its run is still alive.

## When the output directory is unwritable

- **At start:** if the run directory or the initial manifest cannot be created, the run context
  reports it. Recording is a best-effort side channel, so the policy is configurable (continue the
  run without recording, or refuse to start), but it is an explicit decision, never a silent success.
- **During the run:** recording never fails the run (the recorder drops or aborts internally and
  accounts for it). An atomic manifest replacement that fails keeps the last good manifest, is
  logged, and yields `recordingStatus` = `failed` at finalize (or `unknown` if the recorder's own
  state could not be read).

## One convention, two implementations

The path convention lives in exactly one place per language:

- `pulse-viewer/reference/run_layout.py` (this repo) for the viewer and Python tools: resolve the
  root, map mode to tier, build a run directory, and read/write/list `run.json`.
- a matching small Java helper for the engine-side run context and recorder (an integration task,
  to be added when the recorder lands in pulse-beacon).

Both agree byte-for-byte on the directory shape and manifest, the same way the datum manifest is
computed once and baked in both languages.

## Viewer integration

The viewer points at `$PULSE_OUTPUT` and shows a **run browser**: the runs from all `run.json` files,
grouped by mode and app, sorted newest-first, showing runId, time, mode, status, event count, and
drops. Selecting a run opens its `events.jsonl`. A File -> Open still loads any recording directly.
This replaces the current hardcoded temp path and fabricated sample.

## Open items

- Default root: user-global `~/.pulse/runs` vs project-local `./pulse-output`.
- Retention/cleanup of old run directories (a max count or age), or leave it to the user.
- Whether `run.json` should also record the launcher command / parameters for full reproducibility.
</content>
