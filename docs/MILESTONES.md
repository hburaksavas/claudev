# Milestones

Re-baselined after the first running build. The original M0–M3 framing is preserved at the bottom
for continuity, but the work packages below are the actionable plan.

## Where we actually are

| Area | State |
|---|---|
| `domain-core` | Done — all aggregates as immutable records |
| `provider-api` | Done — 4 ports, DTOs, `ProviderResult`/`ProviderError`, `AdapterManifest` |
| `platform-windows` | **Done for WP1's scope** — Job Object create/assign/terminate/close, `CreateProcessW`-suspended spawn, argv quoting, explicit environment blocks, and pid+creation-time+fingerprint identity verification are all real and verified against live Win32 APIs |
| `persistence` | **Done for WP2's scope** — schema (9 tables), migration runner, repositories for Workspace/Instance/LaunchRecord/AuditEntry, optimistic concurrency, all real and verified |
| `secret-store-dpapi` / `-legacy` | Done — real DPAPI round trip, verified live |
| `app-bootstrap` / `ui-shell` | Single-JVM lifecycle works; UI is a diagnostics screen only |
| `operation-engine` | Interface only — no scheduler, no event bus |
| `adapter-rabbitmq` / `-redis` / `-fe-pipeline` | Stubs returning `NOT_IMPLEMENTED` |
| `packaging` | Placeholder |

## The critical path

WP1 (spawn primitive) and WP2 (persistence) are both done and verified. WP3 (reconciler) is next —
it needs both: WP1's `ProcessIdentity`/`WindowsProcessLauncher` to check live process state, and
WP2's `InstanceRepository`/`LaunchRecordRepository` to read/write what it finds.

WP3 → WP4 is now the strict remaining chain. WP5 can start once WP3 lands. WP6/WP7/WP8 are
parallelizable once WP4 is done.

---

## WP1 — Process spawn primitive (`platform-windows`) — DONE

The foundation. Discharges three documented spikes (see the ledger below); the fourth
(app-already-inside-an-external-job) remains open and is called out separately.

**Built:**
- `Kernel32Ext` extended with `CreateProcessW`, `ResumeThread`, `TerminateProcess`,
  `GetProcessTimes`, `QueryFullProcessImageNameW`, `GetExitCodeProcess`, `WaitForSingleObject`,
  `STARTUPINFOW`/`PROCESS_INFORMATION`/`FILETIME` structs, and the `CREATE_SUSPENDED` /
  `CREATE_UNICODE_ENVIRONMENT` / `CREATE_NO_WINDOW` flags.
- `WindowsCommandLine` — argv list to Win32 command-line string via the standard `ArgvQuote`
  algorithm. Its own class, its own adversarial test suite (`WindowsCommandLineTest`), 13
  parameterized cases including embedded quotes, trailing backslashes, `&|<>^`, and empty strings —
  round-tripped through the *real* `CommandLineToArgvW` (via a test-only `shell32.dll` binding),
  not a hand-rolled re-implementation of the parser.
- `WindowsEnvironmentBlock` — explicit, sorted, UTF-16LE double-null-terminated environment block;
  no merge with the caller's own environment, ever (`WindowsEnvironmentBlockTest`).
- `WindowsProcessLauncher` — the full D5 ordering (suspend, create job, assign, verify, resume),
  returning `LaunchResult` (pid, creation `Instant`, OS-resolved image path, SHA-256 fingerprint,
  the open `WindowsJobObject`). On any failure after `CreateProcessW` succeeds, the suspended child
  is `TerminateProcess`'d before the exception propagates — no code path leaves an untracked
  suspended process behind.
- `ProcessIdentity.lookup`/`verify` — pid + creation time + image-path SHA-256 fingerprint;
  `WindowsProcessQuery` holds the shared low-level reads so `ProcessIdentity` and
  `WindowsProcessLauncher` don't duplicate the FILETIME/image-path/hashing logic.

**Verified (`WindowsProcessLauncherTest`, real spawns on this machine, not mocked):**
- Launch `cmd.exe /c "ping ..."`, wait for `ping.exe` to appear as a real OS grandchild via
  `ProcessHandle.children()`, terminate the job, confirm both `cmd.exe` and the grandchild die —
  the `mvn.cmd`/`cmd.exe`/`java.exe` chain shape from PROCESS_SAFETY.md.
- Identity verifies while the process is alive; once it exits naturally, the same recorded
  `(pid, creationTime, fingerprint)` never verifies again, and a mismatched `creationTime` against
  the same pid also fails — the concrete PID-reuse defense.
- The reported fingerprint matches an independently computed SHA-256 of the actual binary on disk.

**Risk:** the "app already inside an external restrictive Job" case (Spike B) still needs a
separate harness — not covered by the tests above, which all run in a plain, unrestricted
developer session. Tracked as open in the spike ledger.

---

## WP2 — Schema, migrations, repositories (`persistence`) — DONE

**Built:**
- `MigrationRunner` — a small, self-contained ordered-SQL runner, not Flyway: checked directly
  against Maven Central first, and there is no `flyway-database-sqlite` artifact (nor any other
  published Flyway module claiming SQLite support) — Flyway was never actually available here, not
  merely heavier. Migrations are `V<version>__<description>.sql` classpath resources, applied in
  order inside one transaction each, tracked in a `schema_version` table with a checksum that fails
  loudly if an already-applied migration's content changes.
- `V1__initial_schema.sql` — all nine tables (`workspace`, `runtime_definition`, `instance`,
  `launch_record`, `connection`, `pipeline`, `operation`, `operation_events`, `audit_entry`).
- `WorkspaceRepository`, `InstanceRepository`, `LaunchRecordRepository`, `AuditEntryRepository` on
  plain `JdbcTemplate`. `Versioned<T>` carries the optimistic-concurrency revision *outside*
  domain-core (revision is persistence infrastructure, not a domain concept, mirroring the
  domain/DTO split in docs/PLUGIN_CONTRACT.md). `AuditEntryRepository` has no update/delete method
  at all — enforcement by absence, not convention.
- `PersistenceConfig` runs migrations as part of building the `DataSource` bean, so every other bean
  that depends on it can assume the schema already exists.

**Verified (real SQLite files on disk, not in-memory/mocked):**
- `WorkspaceInstanceRoundTripTest` — write through one `DataSource`, read back through a second,
  independently-opened `DataSource` against the same file (simulating "the app restarted" the way
  that actually matters: nothing but the on-disk file carries the data across).
- `WorkspaceOptimisticLockTest` — a second writer at a stale revision is rejected
  (`OptimisticLockException`), and the row reflects only the winning writer's change.
- `LaunchRecordDurabilityTest` — after `save()` returns, a completely separate `DriverManager`
  connection (bypassing the repository/DataSource under test) already sees the row; a second save
  atomically replaces the first via `INSERT OR REPLACE`, never accumulating history.
- `MigrationRunnerTest` — schema applies once, is idempotent on rerun, and a tampered checksum is
  rejected.

**A real bug found and fixed along the way:** xerial sqlite-jdbc throws
`"The prepared statement has been finalized"` when a comment-only SQL fragment reaches
`Statement.execute()` — confirmed by isolated reproduction. The actual trigger was a semicolon
*inside a migration file's own header comment*, which broke a naive split-on-`;` approach mid
comment. Fixed by stripping `--` comments before splitting on semicolons, not by special-casing
comment-only chunks after the fact. See docs/PERSISTENCE.md for the full account — worth reading
before touching `MigrationRunner` again.

---

## WP3 — Reconciler

**Build:**
- `Reconciler` service: for every instance with a `LaunchRecord`, derive state from pid existence →
  creation-time match → image fingerprint match → adapter health probe. Never trust a stored
  `RUNNING`.
- Untracked detection: enumerate processes whose image path resolves under the app's
  managed-binaries directory, diff against known `LaunchRecord`s, classify the remainder as
  `UNTRACKED` (needs `EnumProcesses` in `platform-windows`).
- Blocking startup pass before the UI shows; timer-driven passes afterwards.

**Acceptance (this is the original M0 gate):** hard-kill the app with several dummy instances
running, relaunch, and every process is classified correctly — no false `RUNNING`, no false
`ORPHANED` for genuinely-dead entries, an unrecorded-but-running process surfaces as `UNTRACKED`.
No kill/signal fires against a pid whose creation-time+fingerprint doesn't match.

---

## WP4 — Operation engine

**Build:**
- DAG scheduler with bounded concurrency; a failed node skips only its dependents; terminal status
  `PARTIALLY_FAILED` with a structured per-node summary.
- Cancellation propagation, per-step timeout, retry policy, compensation only where explicitly
  declared safe.
- Event bus: one `OperationEvent` stream, forwarded live to the UI *and* appended to
  `operation_events` — same event, not two representations.
- A `DummyRuntimeProvider` that spawns a trivial long-lived child via WP1, so WP1–WP4 can be
  exercised end to end without RabbitMQ existing yet.

**Acceptance:** a DAG with one deliberately failing node reports `PARTIALLY_FAILED` listing exactly
that node's dependents as skipped and unrelated branches as succeeded; cancelling mid-run stops
pending nodes and leaves no orphaned process; the persisted event stream alone is enough to
reconstruct what happened after a crash.

---

## WP5 — Workspace UI (`ui-shell`)

Can start once WP3 lands; this is where the app becomes usable rather than inspectable.

**Build:** workspace list/create/delete; instance list showing live reconciler state; start/stop
actions dispatched as operations; live operation progress and log tail; the persistent
"not encrypted" badge for anything backed by the legacy secret store (D13); a tray icon so
window-close can finally mean minimize-to-tray as PROCESS_SAFETY.md intends.

**Acceptance:** starting and stopping a dummy instance from the UI is reflected in reconciler state
without a manual refresh; closing to tray and restoring works; killing the instance externally
flips the UI to `ORPHANED` within one reconcile interval.

---

## WP6 — RabbitMQ adapter

**Blocked on Spike A** (see [RABBITMQ_RUNTIME.md](RABBITMQ_RUNTIME.md)) — run the spike before
committing to this design.

**Build:** pinned RabbitMQ+Erlang pair acquisition with checksum verification; per-instance
nodename/ports/cookie/data-dir isolation via WP1's explicit environment block; EPMD as a
ref-counted application-level dependency never owned by a single instance's job; readiness via the
management API, not port occupancy.

**Acceptance:** two simultaneous instances under a non-ASCII (Turkish) profile path; stopping one
disturbs neither the other nor a pre-existing external EPMD; app crash leaves no orphaned Erlang VM.

---

## WP7 — Redis adapter

**Build:** Lettuce-backed connections (remote + imported/system only); `environmentClass` gating
with unset treated as production-restrictive; SCAN paging with the "possibly-incomplete live view"
affordance; the typed edit set from [REDIS_SCOPE.md](REDIS_SCOPE.md); the authorization+audit gate
on *every* mutation, plus `prepareMutation`/`commitMutation` for bulk/glob/flush-class operations.

**Acceptance:** a scripted single-key mutation against an unlocked-but-unclassified connection is
refused by policy (not merely hidden in the UI); a glob delete without a completed token round trip
is refused; the token is rejected when underlying data changed since preview, not only on expiry.

---

## WP8 — FE pipeline adapter

**Build:** the step catalog (`EnsureCheckout` … `HealthCheck`) on WP1's spawn/argv primitives;
`ExecStep` with its allow-list and explicit env; `ConfigPatch` schema-validated substitution against
declared files only; system proxy + corporate CA honored by the Git/Maven steps.

**Acceptance:** a real pipeline run against a test Bitbucket repo and Maven project behind a
proxy/CA; pre-execution argv is logged and inspectable; the WP1 adversarial argv suite is re-run
through the actual Git/Maven step paths, not just the launcher.

---

## WP9 — Orchestration, logging, packaging

**Build:** Start/Stop/Update/Restart All as DAG operations; log rotation + pre-write redaction per
the deferred log ADR; the bounded graceful shutdown currently stubbed as a `TODO` in
`ClaudevApplication.gracefulShutdown`; jlink + jpackage installer (code-signed); licensing/SBOM
check gating release builds.

**Acceptance:** installs and runs without admin on a clean VM (ASCII and Turkish profile paths);
the SBOM check blocks a build that can't attest the bundled pair's licenses; a hard kill mid-`Update
All` leaves state that reconciles cleanly on next launch.

---

## Spike status

| Spike | Status |
|---|---|
| Job Object create/assign/terminate | **Discharged** — `WindowsJobObjectSmokeTest`, real process killed via job |
| Spawn-suspended ordering + grandchild kill | **Discharged** — `WindowsProcessLauncherTest`, real `cmd.exe`→`ping.exe` tree killed via job |
| PID-reuse identity verification | **Discharged** — `WindowsProcessLauncherTest`, verify() rejects a recorded identity after real process exit |
| Windows argv quoting / injection | **Discharged** — `WindowsCommandLineTest`, 13 adversarial cases round-tripped through the real `CommandLineToArgvW` |
| App inside an external restrictive Job | Open — not covered by WP1's tests, which all ran in a plain developer session |
| DPAPI round trip | **Discharged** for the core case — `DpapiSecretStoreTest`; profile-reset/roaming case still open |
| SQLite WAL + busy_timeout applied | **Discharged** — `PragmaAppliedDataSourceTest` (found a real bug: pragmas were applied once instead of per connection) |
| SQLite contention under real-time AV load | Open — WP2 |
| RabbitMQ + Erlang two-node portable pair | Open — blocks WP6 |
| jlink/jpackage no-admin install | Open — WP9 |

---

## Original framing (superseded, kept for continuity)

M0 safety core → M1 RabbitMQ + Redis connections + secrets → M2 Redis typed edit set + FE pipeline
→ M3 workspace orchestration + packaging. WP1–WP4 are M0; WP6–WP7 are M1; WP7–WP8 are M2; WP9 is
M3. Deferred past all of it, unchanged: Redis arbitrary CLI/console, the out-of-process plugin
protocol, dashboards, marketplace, cloud/team sync.
