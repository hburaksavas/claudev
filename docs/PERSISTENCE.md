# Persistence

## SQLite, WAL mode

`SqlitePragmaConfigurer` (in `persistence`, real and test-verified) applies these to a connection:

```sql
PRAGMA journal_mode=WAL;
PRAGMA foreign_keys=ON;
PRAGMA busy_timeout=<tuned value>;
```

WAL + a tuned `busy_timeout` exist specifically because real-time AV scanning of the DB file is an
expected, not-fully-eliminable source of transient lock contention on Windows — see Spike C in
[RISK_REGISTER.md](RISK_REGISTER.md). The exact `busy_timeout` value should be set from that
spike's measured contention, not guessed.

**Per-connection, not once at startup.** `journal_mode=WAL` is stored in the database file and
survives across connections, but `busy_timeout` and `foreign_keys` are per-connection settings
that revert to the driver's defaults on every new connection. `PragmaAppliedDataSource` wraps the
underlying `DataSource` and applies the pragmas to every connection it hands out. This is not a
theoretical concern: the first run of the real application showed `busy_timeout=3000` (the
sqlite-jdbc default) in the diagnostics view while the code "configured" 5000 at startup — WAL read
back correctly, so the misconfiguration was invisible until the value was displayed.
`PragmaAppliedDataSourceTest` is the regression test.

## What lives where

- **SQLite**: aggregate definitions and last-known state (indexes/metadata), immutable operation
  plans, append-only `operation_events` and `audit_entries` tables.
- **Filesystem**: logs, build artifacts, generated runtime config — versioned/hashed paths, not the
  database.

Secrets resolve only at execution time via `SecretStore.resolve()` and must never enter SQLite,
logs, or an immutable operation plan in plaintext — only a `SecretRef.opaqueHandle` does.

## Concurrency model

Optimistic revisions on aggregates (a `Workspace`/`Instance` write includes the revision it read;
a stale write is rejected, not silently overwritten). Operation plans, once created, are immutable
— an `Operation`'s `dag` does not change after submission; cancellation and status changes are
new writes, not edits to the plan.

## What exists today vs. what's still backlog

`PersistenceConfig` provides a real `DataSource` bean — a single SQLite file under
`~/.claudev/claudev.db` (user-space, no admin path assumed) — with `SqlitePragmaConfigurer`'s
WAL/busy_timeout pragmas applied to *every* connection via `PragmaAppliedDataSource`, and
`MigrationRunner` bringing the schema up to date before the bean is returned. Supplying the bean
makes Spring Boot's own `DataSourceAutoConfiguration` back off automatically
(`@ConditionalOnMissingBean(DataSource.class)`).

**Migrations**: a small, self-contained ordered-SQL runner (`MigrationRunner`), not Flyway —
checked directly against Maven Central before choosing: there is no `flyway-database-sqlite`
artifact, and no other published Flyway module claims SQLite support. Migrations are classpath
resources named `V<version>__<description>.sql` under `db/migration/`, applied in order inside one
transaction each, tracked in a `schema_version` table with a checksum that fails the run loudly if
an already-applied migration's content has changed.

**Schema** (`V1__initial_schema.sql`): all nine tables from the architecture doc exist —
`workspace`, `runtime_definition`, `instance`, `launch_record`, `connection`, `pipeline`,
`operation`, `operation_events`, `audit_entry`. Polymorphic sub-structures (`RuntimeSource`,
`RedisSafetyPolicy`, the pipeline step list) are stored as JSON text columns rather than normalized
per-variant — a pragmatic choice for a single-writer embedded database.

**Repositories** (`WorkspaceRepository`, `InstanceRepository`, `LaunchRecordRepository`,
`AuditEntryRepository`, on plain `JdbcTemplate`) — the four this codebase's Build backlog called
for; `connection`/`pipeline`/`operation` tables exist but have no repository yet, deferred to the
milestone that actually needs them (WP4/WP7/WP8) rather than built speculatively now.

**Optimistic concurrency**: `Versioned<T>` pairs a domain aggregate with its revision.
Deliberately *not* a field on the domain-core records themselves — revision is persistence
infrastructure, not a domain concept, the same separation `provider-api`'s DTOs already keep from
domain types. `WorkspaceRepository.update`/`InstanceRepository.update` take an
`expectedRevision` and throw `OptimisticLockException` on mismatch, leaving the stored row
untouched.

**Append-only tables**: `AuditEntryRepository` has no update/delete method — the enforcement is the
absence of the method, not a documented convention.

Still missing: a pooled/production-grade `DataSource` (the current one is a plain
`DriverManagerDataSource`, fine for getting the app running but not for concurrent load), and
repositories for the three tables noted above.

### A real bug this surfaced

Writing `MigrationRunner`'s statement-splitting hit a genuine xerial sqlite-jdbc driver bug:
passing a comment-only SQL fragment (no actual statement, just `--` lines) to `Statement.execute()`
throws `"The prepared statement has been finalized"` — confirmed by isolated reproduction, not
guessed. The real trigger was subtler than "skip comment-only chunks," though: one of this file's
own header comments contains a literal `;` ("...see docs/MILESTONES.md WP4/WP7/WP8); their
tables..."), which a naive split-on-`;`-first approach breaks *in the middle of the comment*,
concatenating its tail with the next real statement into something that's neither a valid comment
nor valid SQL. The fix strips `--` comments from every line *before* splitting on semicolons, so a
semicolon inside comment prose can never affect statement boundaries.
