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

## What exists today vs. what's still M0 backlog

`PersistenceConfig` (in `persistence`) provides a real `DataSource` bean — a single SQLite file
under `~/.claudev/claudev.db` (user-space, no admin path assumed), with
`SqlitePragmaConfigurer`'s WAL/busy_timeout pragmas applied on the first connection. Supplying this
bean makes Spring Boot's own `DataSourceAutoConfiguration` back off automatically
(`@ConditionalOnMissingBean(DataSource.class)`), which is what lets `app-bootstrap` actually start —
verified by running `spring-boot:run` for real, not just by a `@SpringBootTest`.

Still missing: schema/migration tooling (Flyway or equivalent), a pooled/production-grade
`DataSource` (the current one is a plain `DriverManagerDataSource`, fine for getting the app
running but not for concurrent load), and the actual `workspace`/`instance`/`operation_events`/
`audit_entries` tables. `ClaudevApplicationTests` still excludes `DataSourceAutoConfiguration` in
its test properties — harmless now that a real bean exists (the exclusion is simply redundant
there), but left in place to avoid an unrelated test-only change.
