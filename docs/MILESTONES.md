# Milestones

Re-baselined after the first running build. The original M0–M3 framing is preserved at the bottom
for continuity, but the work packages below are the actionable plan.

## Where we actually are

| Area | State |
|---|---|
| `domain-core` | Done — all aggregates as immutable records |
| `provider-api` | Done — 4 ports, DTOs, `ProviderResult`/`ProviderError`, `AdapterManifest` |
| `platform-windows` | **Partial** — Job Object create/assign/terminate/close is real and verified; **no process-spawn path exists yet** |
| `persistence` | **Partial** — real `DataSource` with per-connection WAL/busy_timeout pragmas; **no schema, no migrations, no repositories** |
| `secret-store-dpapi` / `-legacy` | Done — real DPAPI round trip, verified live |
| `app-bootstrap` / `ui-shell` | Single-JVM lifecycle works; UI is a diagnostics screen only |
| `operation-engine` | Interface only — no scheduler, no event bus |
| `adapter-rabbitmq` / `-redis` / `-fe-pipeline` | Stubs returning `NOT_IMPLEMENTED` |
| `packaging` | Placeholder |

## The critical path

Everything runtime-related is blocked behind one missing primitive: **spawning a process under a
Job Object with verified identity**. No adapter can be written honestly before it exists, because
the D5 ordering invariant (suspend → create job → assign → verify → resume, `LaunchRecord`
persisted before the spawn call returns) is what makes every later "stop this instance" safe.

WP1 → WP2 → WP3 → WP4 is a strict chain. WP5 can start once WP3 lands. WP6/WP7/WP8 are
parallelizable once WP4 is done.

---

## WP1 — Process spawn primitive (`platform-windows`)

The foundation. Also discharges two documented spikes.

**Build:**
- `Kernel32Ext`: add `CreateProcessW`, `ResumeThread`, `GetProcessTimes`,
  `QueryFullProcessImageNameW`, `GetExitCodeProcess`, `WaitForSingleObject`, plus `STARTUPINFOW`
  and `PROCESS_INFORMATION` structs and the `CREATE_SUSPENDED` / `CREATE_UNICODE_ENVIRONMENT` /
  `CREATE_NO_WINDOW` flags.
- `WindowsCommandLine` — argv list → Win32 command-line string following `CommandLineToArgvW`
  quoting rules. **Security-critical**: this is the mechanism behind the "no shell-string
  injection" release blocker, so it gets its own class and its own adversarial test suite rather
  than being inlined into the launcher.
- `WindowsEnvironmentBlock` — explicit UTF-16, double-null-terminated environment block (required
  by D7: RabbitMQ spawns must pass a fully explicit environment, never inherit the user's shell).
- `WindowsProcessLauncher` — the D5 ordering invariant, returning a value object carrying pid,
  creation FILETIME, resolved image path, and the owning `WindowsJobObject`.
- `ProcessIdentity.verify(...)` — pid + creation time + image path/SHA-256 fingerprint check;
  every kill/signal path must route through it.

**Acceptance:**
- A child spawned suspended is provably inside the job *before* it executes (assert via a child
  that writes a marker file on start — the marker must never appear if the job assignment failed).
- Killing the job kills the whole tree including a grandchild (`cmd.exe` → `java.exe`, the Maven
  shape from PROCESS_SAFETY.md).
- `verify()` rejects a recycled pid: spawn, record identity, kill, spawn a different process,
  assert the old record no longer verifies.
- Adversarial argv test: embedded quotes, trailing backslashes, `&|<>^`, empty string, a fake
  project name containing `" & calc.exe`. Round-trip each through a child that echoes back its own
  parsed `argv` and assert exact equality.

**Risk:** the "app already inside an external restrictive Job" case (Spike B) still needs a
separate harness; do it here while the code is fresh.

---

## WP2 — Schema, migrations, repositories (`persistence`)

**Build:**
- Migration runner (Flyway, or a small ordered-SQL runner — Flyway is the lower-risk default) and
  the initial schema: `workspace`, `runtime_definition`, `instance`, `launch_record`, `connection`,
  `pipeline`, `operation`, `operation_events`, `audit_entry`.
- Repositories for `Workspace`, `Instance`, `LaunchRecord`, `AuditEntry` on `JdbcTemplate`.
- Optimistic `revision` column on mutable aggregates; a stale write is rejected, never silently
  overwritten.
- `operation_events` and `audit_entry` are append-only (no update/delete paths in the repository
  API at all — the absence of the method is the enforcement).

**Acceptance:**
- Create a workspace + instance, restart the app, both load back identically.
- A write with a stale revision fails with a conflict, and the winning row is unchanged.
- `LaunchRecord` save is synchronous and returns only after commit — asserted by a test that
  kills the JVM immediately after save and reads the row from a fresh connection.

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
| Spawn-suspended ordering + grandchild kill | Open — WP1 |
| App inside an external restrictive Job | Open — WP1 |
| Windows argv quoting / injection | Open — WP1 |
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
