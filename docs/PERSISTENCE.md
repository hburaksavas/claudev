# Persistence

## SQLite, WAL mode

`SqlitePragmaConfigurer` (in `persistence`, real and test-verified) applies on every connection:

```sql
PRAGMA journal_mode=WAL;
PRAGMA foreign_keys=ON;
PRAGMA busy_timeout=<tuned value>;
```

WAL + a tuned `busy_timeout` exist specifically because real-time AV scanning of the DB file is an
expected, not-fully-eliminable source of transient lock contention on Windows — see Spike C in
[RISK_REGISTER.md](RISK_REGISTER.md). The exact `busy_timeout` value should be set from that
spike's measured contention, not guessed.

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

## Not yet built

The actual schema/migration tooling (Flyway or equivalent) and the `DataSource` wiring are M0
backlog — `app-bootstrap`'s tests currently exclude `DataSourceAutoConfiguration` for exactly this
reason. Do not read the current absence of a `spring.datasource.url` as a design decision; it's an
unfinished-work marker.
