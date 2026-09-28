# SQL gateways, a design suggestion

Stream events out of a SQL table as a historical source, and write events into a SQL table as a sink.
A table plus a datum type plus a connection is enough to describe either direction.

Nothing here is built yet. This records the decisions taken and, more importantly, *why* — so the
implementation can be judged against them and the open questions are visible rather than discovered
late.

## What the old system had, and what it teaches

The previous framework had `SqlTopicsToTablesWriterGateway` (Java, JDBC) in `JavaCepBase`, beside
`CsvGateway` and `HeartBeatGateway`. It held a `Topic → table` map, created the table from the
datum's field names and types if absent, validated an existing table against the topic, and inserted
each event from a throttled queue so database I/O stayed off the dispatch thread. It refused
`publish()`: sink only.

There was **no SQL source gateway**. Reading was `pandas.read_sql` in the histdata package, producing
a DataFrame handed to a Python `HistoricalBarsPublishingGateway` which republished it. The engine
never read SQL.

Three things worth carrying: the topic↔table mapping, create-or-validate on startup, and the
type-match check. Two worth dropping: rows assembled by string concatenation (injection-prone and
slow), and `logger.info("User: " + dbUser + ", Pswd: " + dbPswd + ...)`, which wrote credentials to
the log in clear text.

## 1. Two gateways over one shared binding

`SqlSourceGateway` and `SqlSinkGateway` are separate classes over a shared `SqlTableBinding`.

Not one gateway with a mode. In Beacon a source and a sink are different *contracts*, not different
configurations: a source is registered with `registerPublisher`, drives the clock, owns a produce
loop and takes part in the TimeMachine's permit protocol; a sink is registered with
`registerSubscriber`, sets `setDriveClock(false)` and receives `onEvent`. `EventRecorderGateway`
already throws `UnsupportedOperationException` from `publish()` because it is sink-only; a combined
class would throw from whichever half is inactive, making half its public API a runtime error decided
by a constructor flag. Their failure policies also differ (§7, §8).

The old `CsvGateway` is the counter-example: one class with nullable `reader` and `writer`, a run
loop branching on `if (reader != null)`, and a `TODO put the publishing and file writing on its own
thread` that survived for years — partly because a combined class leaves "which thread does what"
ambiguous.

**The binding is immutable metadata plus conversion rules — it does not own a connection.** A
snapshot source and a batching sink need different transaction lifetimes, and a shared connection
would force one policy on both. Instead:

- the binding holds the qualified table identity, the datum type, the column mapping, the ordering
  tuple and the storage-mapping version — all immutable and freely shared;
- a `DataSource` is **injected**, so the host application owns pooling, credentials and secrets, and
  tests can substitute one;
- each gateway manages its own connection and transaction lifecycle against that `DataSource`.

A consequence worth designing for rather than discovering: **a source must work with read-only
credentials against a pre-provisioned binding.** Ordinary startup must not require DDL rights or
registry writes. Provisioning is a separate, privileged act (§5).

Credentials are never logged. The old gateway printed them.

## 2. Replay needs a stable ordering tuple

`TimeEventComparator` breaks equal event times by `(originRank, originIndex, sequence)`, where
`sequence` is the order a gateway handed events to the engine. For two rows sharing a timestamp in
one table, **the replay order is the source's read order**. The TimeMachine cannot repair a
non-deterministic source; it faithfully preserves whatever order it was given.

SQL provides no row order without `ORDER BY`, and `ORDER BY <time>` alone is not a total order — ties
can come back differently between runs, engines, or parallel plans.

So the source requires a **stable, unique, non-null ordering tuple**: `(timeColumn, …)`. The declared
primary key is the default way to obtain one, discovered from JDBC `DatabaseMetaData.getPrimaryKeys`,
and the first release may require exactly that for simplicity. But the requirement is the tuple, not
the primary key: a declared unique non-null column set is equally valid, and saying a table "cannot
be replayed" without a primary key overstates the restriction.

The same tuple makes keyset pagination possible, which is how a large table is read in bounded
memory:

```sql
SELECT ... FROM <catalog>.<schema>.<table>
 WHERE  <time> >= :windowStart AND <time> <= :bound
   AND (<time>, <ord…>) > (:lastTime, :lastOrd…)
 ORDER BY <time>, <ord…>
 FETCH FIRST :pageSize ROWS ONLY
```

`OFFSET` is not an alternative: it degrades on large tables and shifts rows under concurrent writes.

Composite tuples are fine. Views and sources with no unique non-null tuple are excluded, and should
say so clearly at startup.

**Indexes warn, they do not refuse.** Without an index on the ordering tuple every page forces a sort
of the window, which is a performance property, not a correctness one — unlike the tuple itself.

## 3. Bound the read always; snapshot where the dialect supports it

Keyset paging reads incrementally, over seconds or minutes. If anything writes to the table
meanwhile — a live feed appending, a backfill correcting history — later pages see data earlier ones
did not, and two runs of nominally the same replay differ. Nothing in the engine can detect this:
each run is internally consistent and deterministic, they simply read different data.

Two mechanisms, applied together:

- **An upper bound, always.** At startup the source fixes a bound (the run window's end, or
  `max(timeColumn)` within it) and every page carries it. Portable, no held transaction, no locks.
  The bound and the row count go into the run manifest, so a changed dataset is detectable after the
  fact even when it cannot be prevented.
- **A snapshot transaction, where it is cheap and well-defined.** `REPEATABLE READ` / snapshot
  isolation gives the database's own consistency guarantee regardless of concurrent writes.

The snapshot is a **per-dialect capability that defaults to off** for any engine not explicitly
characterised. That keeps this one code path plus a small explicit dialect table: the bound is
universal, the snapshot is an enhancement. A held transaction is not free — on PostgreSQL a
long-running snapshot blocks vacuum and causes bloat — so the dialect table records not just whether
snapshot isolation exists but whether holding one for the length of a replay is acceptable.

## 4. The storage mapping is a contract, and it needs writing down

"Compatible SQL type" is not a contract. The existing types already force the hard cases, and
`VectorValue` forces most of them at once: `values` is `array<decimal>`, `valueIds` is an *optional*
`array<string>` carrying a cross-field invariant (`x-parallel-to`), and `format: decimal` declares
**no precision or scale**.

The mapping must therefore define, per schema construct:

- **Decimals.** What `format: decimal` becomes. `NUMERIC(p,s)` needs a declared precision and scale
  that the schema does not currently carry — so either the schema gains them, or the mapping declares
  a default and documents the truncation risk. A value that cannot round-trip exactly is **rejected**,
  not silently coerced.
- **Arrays.** `VectorValue.values` has no single right answer: a native array type, a JSON column, or
  a child table. Each has different query ergonomics and different dialect support, and a child table
  changes the row↔datum mapping from one row to a join. Parallel arrays add the invariant that two
  columns must stay the same length.
- **Optionality.** Schema-optional fields map to nullable columns; required fields to `NOT NULL`. The
  mapping states which, rather than leaving it to the DDL generator's habits.
- **Instants and dates.** UTC semantics stated explicitly, not inherited from session configuration.
- **Round-trip proof.** Every supported mapping has a property test: value → row → value must be
  equal, per dialect, not per "the DDL was accepted".

That last point is the reason dialect coverage cannot be asserted from DDL alone. **SQLite's numeric
affinity will convert a decimal-looking value to floating point**, so a table whose DDL says
`NUMERIC` there does not carry the guarantee it carries on PostgreSQL. Accepting the same DDL proves
nothing about the same behaviour.

**Split the mapping in two.** The *logical* mapping — datum field → logical SQL type, nullability,
ordering role — is generated in pulse-data, beside the Java record and the Pydantic model, keeping
the one-definition property. The *dialect rendering* — concrete DDL, indexes, generated columns,
upsert syntax, migration policy — lives in the adapter, because it is where dialect knowledge belongs
and it changes on a different cadence.

**Record a storage-mapping version alongside the datum fingerprint.** The same datum schema can have
more than one valid SQL representation (arrays as JSON in v1, as a child table in v2), so the
registry must distinguish them or a v2 reader will silently misread a v1 table.

## 5. A table declares its type in a registry

A `pulse_tables` registry maps a data table to the type it holds:

| column | meaning |
| --- | --- |
| `catalogName`, `schemaName`, `tableName` | the fully qualified table — name alone is ambiguous |
| `typeId` | e.g. `com.inventzia.pulse.data.schemas.marketdata.CdfBar` |
| `typeVersion` | the datum's `TYPE_VERSION` |
| `schemaFingerprint` | the per-type `sha256` from pulse-data's provider manifest |
| `storageMappingVersion` | which SQL representation of that schema (§4) |
| `state` | `provisioning` / `ready` (see below) |
| `createdAt`, `createdBy` | provenance |

One row per table rather than per event, and the fingerprint catches **same type, changed schema**,
which a bare type identifier waves through.

**Creating the table and registering it cannot be made atomic portably.** MySQL performs an implicit
commit on `CREATE TABLE`, so the caller's rollback cannot undo it, and a crash between the two
statements leaves an unregistered table. Rather than claim a guarantee that only some dialects can
keep, provisioning is an explicit state machine: insert the registry row as `provisioning`, create
the table, then mark it `ready`. A `provisioning` row with no table, or a table with no row, is a
recoverable state the adapter can report and resolve, not a corruption.

A table found without a registry row is refused unless the caller explicitly asserts the binding,
which then records it — the migration path for existing tables, deliberately an explicit act.

**Source and sink validate differently**, and conflating them would be a bug:

- A **source** needs every datum field present and readable with a compatible type. Extra columns are
  irrelevant to it.
- A **sink** additionally needs every extra column to be nullable or defaulted. An extra `NOT NULL`
  column without a default is harmless to reading and makes *every insert fail* — so the sink must
  check it at startup rather than on the first write.

## 6. Event time is BIGINT, with an optional derived timestamp

A datum's `x-datum-time` field is epoch milliseconds (`int64`). The canonical column is `BIGINT`:
exact, dialect-independent, trivially ordered, and the column the index and the keyset paging use.

Beside it, optionally, a `TIMESTAMP(3)` column **derived by the database** (generated/computed
columns exist in PostgreSQL, MySQL, SQL Server and SQLite). Because it is derived rather than
written, the two cannot disagree. It exists because the point of putting events in a database rather
than the JSONL the recorder already writes is largely that *other tools can query them*, and a table
of opaque `1790151393780` values does not deliver that.

It is a **dialect-specific convenience, not part of the contract**: explicitly UTC, never read back by
Pulse, and absent on dialects where it cannot be generated rather than emulated by a written column
that could drift.

## 7. Delivery semantics: three outcomes, not two

`observed` versus `written` is sufficient for the recorder, which owns its file. It is **not**
sufficient here. If the database commits a batch and the connection fails before the acknowledgement
arrives, the sink genuinely does not know whether those rows landed — PostgreSQL documents this
explicitly for connection loss and failover. Classifying them as written would be a lie; classifying
them as lost would also be a lie; and blindly retrying an append-only insert may duplicate them.

So the sink accounts for three outcomes:

| outcome | meaning |
| --- | --- |
| `committed` | the commit was acknowledged |
| `notCommitted` | the transaction demonstrably failed or rolled back |
| `unknown` | the outcome could not be established — connection lost at commit, failover |

All three go into the run manifest. `unknown` must never be silently folded into either neighbour.

**Retry needs an ingestion identity, which is not the natural key.** Each event carries a
deterministic `ingestionId` — derived from `(runId, sequence)`, so it is stable across retries of the
same event — under a unique constraint. A retry then either inserts, or violates that constraint,
which *proves* the earlier attempt committed and resolves `unknown` into `committed`. Deduplication
becomes a fact rather than a guess.

This is deliberately distinct from the **opt-in natural-key upsert** (business key + event time),
which exists so that re-running a strategy converges rather than duplicating. Using the natural key
for retry safety would silently overwrite two legitimately distinct events that share a business key
and timestamp. The two mechanisms answer different questions and both are needed.

**The sink's failure policy is configurable, with an observational default.** "Never kill the run" is
right for a sink that records alongside the real output — it matches `RunRecording`, and killing a
live run because a disk filled is worse than losing its recording. It is too absolute for a general
persistence gateway: an application whose essential output *is* the database should be able to say
so, and have a persistence failure fail the run. Default observational, declare otherwise explicitly.

## 8. A failing source needs an engine contract, not an exception

A historical source that fails mid-replay should fail the run: a result derived from partial history
is worse than no result. **The current framework cannot express that**, and copying the existing
pattern would not establish it.

`JsonlReaderGateway` catches its own exceptions, logs SEVERE, disconnects and sets itself `STOPPED`
— deliberately, so the TimeMachine's all-drivers barrier is released rather than hanging the run. But
the *engine* never learns. Its own execution path did not throw, so it completes normally and the run
manifest records `runStatus: completed` for a replay that silently stopped halfway. Five tests assert
`isIn(STOPPED, COMPLETE)` on the engine, which accepts exactly this ambiguity.

So this is engine work, not gateway work, and it needs specifying before the source is written:

- how a source **reports terminal failure** to the engine (a counterpart to `RunListener`, or a
  failure channel on the gateway contract);
- how the TimeMachine **barrier is released** without the failure being mistaken for clean
  end-of-stream — the two are currently indistinguishable;
- how outstanding reads and other sources are **cancelled**, rather than left to finish into a run
  that is already doomed;
- how that surfaces as `runStatus: failed` with the failing gateway named.

Until that exists, a SQL source can be honest only to the extent the recorder is: report the failure
in its own accounting and in the manifest. That is strictly weaker than the guarantee wanted, and the
document should not claim otherwise.

## 9. Where it lives

`com.inventzia.pulse.beacon.core.gateway.database`, in pulse-beacon core, beside `gateway.recording`
and `gateway.periodic` — where the old system put it too.

It adds no bundled runtime dependency: `java.sql` is in the JDK, beacon runs in classpath mode with
no `module-info`, and the JDBC driver is supplied by the user like the JDK itself. The only new
dependency is test-scope.

That is a supporting argument, not a decisive one — dependency count alone should not settle every
future module boundary. The substantive reason is that SQL is *generic infrastructure with no
vendor*, as CSV and JSONL are, whereas a broker API brings vendor licensing, credentials and
market-specific semantics and genuinely wants isolation. When vendor adapters multiply, one
`pulse-adapters` multi-module repo beats one repo each.

**Java, not Python.** The source drives the clock: every row crosses into the engine, and in a
compressed-time replay a per-event bridge hop lands on the hot path of the component whose
determinism and throughput everything else rests on. SQLAlchemy would have solved more of the dialect
work than JDBC does, and that cost is accepted knowingly.

## Open questions

- **Decimal precision.** Does `format: decimal` gain explicit precision/scale in the schema, or does
  the mapping declare a default? The first is more correct and touches every existing type.
- **Array representation.** JSON column, native array, or child table for `VectorValue.values` — and
  whether the answer may differ per dialect, which the storage-mapping version would have to encode.
- **Engine failure contract.** §8 is a prerequisite, and it is engine work with its own design.
  Whether it lands before, with, or after the SQL source is a sequencing decision.
- **Provisioning rights.** Sink provisioning needs DDL permission; sources need none. Is provisioning
  a separate administrative entry point rather than something a run does at all?
- **Sink table lifecycle.** A run whose target table already holds a previous run's rows — append,
  refuse, or partition by `runId`?

## Recommended sequence

1. **Storage mapping and delivery semantics** (§4, §7) — the two contracts everything else encodes.
   Defining them late means rewriting what was built on the assumptions.
2. **Binding and registry** (§1, §5), including the provisioning states.
3. **Sink** — the simpler contract, no clock-driving constraint, and it exercises the mapping,
   registry and generated DDL end to end.
4. **Source failure propagation** (§8) — engine work, and a prerequisite for an honest source.
5. **Source with snapshot replay** (§2, §3), building on a mapping already proven by the sink.

Test against an embedded database **and the first real production engine** from the start. The
embedded one keeps CI fast; only the real one proves the guarantees — SQLite accepting `NUMERIC` DDL
says nothing about whether it preserved your decimals.
