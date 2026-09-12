# Milestones

Re-baselined after the first running build. The original M0–M3 framing is preserved at the bottom
for continuity, but the work packages below are the actionable plan.

## Where we actually are

| Area | State |
|---|---|
| `domain-core` | Done — all aggregates as immutable records |
| `provider-api` | Done — 4 ports, DTOs, `ProviderResult`/`ProviderError`, `AdapterManifest` |
| `platform-windows` | **Done for WP1 + WP8's needs** — Job Object create/assign/terminate/close, `CreateProcessW`-suspended spawn, argv quoting, explicit environment blocks, pid+creation-time+fingerprint identity verification, process enumeration, exit-code waiting, and stdout/stderr redirection are all real and verified against live Win32 APIs |
| `persistence` | **Done for WP2's scope** — schema (9 tables), migration runner, repositories for Workspace/Instance/LaunchRecord/AuditEntry, optimistic concurrency, all real and verified |
| `secret-store-dpapi` / `-legacy` | Done — real DPAPI round trip, verified live |
| `app-bootstrap` / `ui-shell` | Single-JVM lifecycle works; UI is a diagnostics screen only |
| `operation-engine` | `Reconciler` and the DAG scheduler/event bus (`InMemoryOperationEngine`) **both done**, real and verified |
| `adapter-fe-pipeline` | **Done** — the full step catalog + `ExecStep`, real, tested against a real public git repo and a real local Maven build |
| `adapter-rabbitmq` / `-redis` | Stubs returning `NOT_IMPLEMENTED` |
| `packaging` | Placeholder |

## The critical path

WP1-WP4 (the safety core and the operation engine) and WP8 (the FE pipeline adapter) are all done
and verified. The app can observe reality, act on it via a real DAG, *and* actually check out/build/
deploy something for real.

WP5 (workspace UI) and WP6/WP7 (RabbitMQ, Redis) remain, both still unblocked and independently
parallelizable — WP6 needs its own feasibility spike (RABBITMQ_RUNTIME.md) before committing to an
implementation, WP7 has no such blocker but has no real Redis/Docker available in this environment
to verify against for real, which is why WP8 (buildable/testable entirely with tools already on
this machine — git, Maven, a local HTTP server) was picked up first, out of the original
alphabetical-ish WP6/WP7/WP8 ordering. WP9 (packaging) still waits on WP5-7 producing something more
worth packaging.

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

## WP3 — Reconciler — DONE

**Built:**
- `platform-windows`'s `RunningProcessScanner` — `K32EnumProcesses` (exported directly from
  kernel32.dll on Vista+, no separate psapi.dll dependency needed) enumerates every live pid,
  filtered down to ones whose image path resolves under a given directory.
- `operation-engine`'s `Reconciler` (deliberately framework-agnostic, no Spring dependency, wired
  as a bean by `app-bootstrap` the same way the adapters are): for every instance, derive state from
  `LaunchRecord` presence → `ProcessIdentity.verify` (pid + creation-time + fingerprint, from WP1) →
  `RUNNING`/`STOPPED`/`ORPHANED`. A failed verification deletes the stale `LaunchRecord` rather than
  leaving a row that can only ever fail again. Separately, any live process under the managed
  binaries directory not accounted for by a tracked pid is reported `UNTRACKED`. The
  adapter-health-probe step from the original algorithm is a documented future extension point (no
  adapter exists yet to probe) — verified process identity alone is `RUNNING` for now.
- `ReconcilerStartupRunner` (a Spring `ApplicationRunner`, which Boot runs synchronously before
  `SpringApplicationBuilder.run()` returns — this is what makes it a genuine *blocking* pass, not
  merely an early one) plus `ReconcilerScheduler` (`@Scheduled`, every 30s) in `app-bootstrap`.
- A "Reconciler dry run" row on the diagnostics screen, running a real pass against the live DB on
  every refresh.

**Verified (`ReconcilerTest`, real spawned processes + real SQLite, not mocked):**
- A genuinely running process (spawned via WP1's `WindowsProcessLauncher`) is classified `RUNNING`;
  an instance with no `LaunchRecord` at all is classified `STOPPED`.
- Killing that process out-of-band, then reconciling again, classifies it `ORPHANED` and confirms
  the stale `LaunchRecord` row is gone.
- A process spawned under the managed directory with no `LaunchRecord`/`Instance` at all is
  reported in `untrackedPids`.

This was the original M0 acceptance gate (hard-kill with several instances running, relaunch,
correct classification, no kill/signal against a mismatched identity) — met, just exercised at the
unit-test level with real spawned processes rather than a full external-process hard-kill drill
against the packaged app. That fuller drill remains useful before shipping (see
docs/TESTING_STRATEGY.md) but is no longer required to prove the reconciler's core logic correct.

---

## WP4 — Operation engine — DONE

**Built:**
- `DagScheduler` (package-private — `OperationEngine`/`OperationPlan`/`OperationNode`/
  `NodeAction`/`NodeExecutionContext` are the public surface, the algorithm itself is an
  implementation detail): bounded concurrency via `ExecutorCompletionService`; a failed node's
  *transitive* dependents are `SKIPPED` (BFS over the reverse dependency graph), unrelated branches
  run to completion regardless; a dangling dependency or duplicate node id is rejected upfront
  rather than discovered mid-run; a dependency cycle fails loudly (`FAILED`, with a clear message)
  instead of hanging forever.
- `NodeAction`/`NodeExecutionContext` — the executable half of a node lives in `operation-engine`
  only, never in `domain-core`'s `OperationDag` (which stays pure structural data — id/dependsOn —
  mirroring the domain/wire-DTO split already used for `provider-api`, D2).
- `InMemoryOperationEngine` (the real `OperationEngine`): `submit` persists the plan and returns
  immediately, running the DAG on a background thread — a deliberate redesign from the original
  stub interface, which returned a fully-realized `Operation` synchronously and was incompatible
  with meaningful cancellation. `cancel` is cooperative (checked via `NodeExecutionContext.isCancelled()`,
  never a forced kill). Every emitted `OperationEvent` is both persisted
  (`OperationEventRepository`, append-only, same enforcement-by-absence rule as `AuditEntryRepository`)
  and pushed to live `subscribe()`rs — the same event, not two representations.
- `OperationRepository`/`OperationEventRepository` (`persistence`): `dag`/`targets`/`kind` are
  immutable once inserted; only `status`/`endedAt` are ever updated. Added `jackson-databind`
  (version managed by the already-imported Spring Boot BOM) to encode the polymorphic `dag`/
  `targets` fields as JSON text columns — hand-mapped to/from plain `Map`/`List` shapes rather than
  letting Jackson reflect over the domain-core record directly, so domain-core stays free of any
  Jackson coupling.
- `DummyRuntimeProvider` (test-only): spawns a real, trivial, long-lived child via WP1's
  `WindowsProcessLauncher`, so WP1-WP4 are exercised end to end through the actual
  `RuntimeProvider` port shape, not just raw `NodeAction`s.
- A "Reconciler dry run" companion — an "Operation engine self-test" row on the diagnostics screen,
  submitting a real one-node operation through the real engine on every refresh.

**Verified (real spawned processes + real SQLite where it matters, not mocked):**
- `DagSchedulerTest` (pure, fast, no I/O): linear-chain ordering; a failed node skips only its
  transitive dependents while an unrelated branch still succeeds (→ `PARTIALLY_FAILED`); a plan
  where *everything* fails is plain `FAILED`, not `PARTIALLY_FAILED`; cancelling mid-run (via a
  blocked node + `CountDownLatch`) stops the pending dependent from ever starting; bounded
  concurrency is never exceeded (measured with an `AtomicInteger` high-water mark); dangling
  dependencies, duplicate ids, and cycles are all rejected/handled without hanging.
- `InMemoryOperationEngineTest`: a plan with a real WP1 spawn node succeeds, and the persisted
  event stream exactly matches what was published live to `subscribe()`; a `PARTIALLY_FAILED`
  operation is fully reconstructable from `find()` — i.e. from the database alone, not leftover
  in-memory state; cancelling a submitted operation after its first (real, spawned) node has
  started stops the dependent node from ever running and leaves **no orphaned process** —
  confirmed by directly checking the spawned pid is no longer alive.

**A real bug found while writing the tests, not the production code:** two tests (and
`DummyRuntimeProvider.stop`) spawned a copy of `ping.exe` into a `@TempDir`-managed directory,
terminated it, and returned — racing JUnit's own directory cleanup against Windows still holding
the executable's file lock briefly after the process was confirmed no longer alive (the same
AV/EDR-adjacent delay class already documented for *writes* in docs/PROCESS_SAFETY.md, observed
here on *delete*). Fixed by waiting for exit and then retry-deleting the specific file with
backoff before the test method returns, rather than assuming "process not alive" means "file
unlocked."

**Acceptance criteria met:** a DAG with one deliberately failing node reports `PARTIALLY_FAILED`
listing exactly that node's dependents as skipped and unrelated branches as succeeded; cancelling
mid-run stops pending nodes and leaves no orphaned process; the persisted event stream alone is
enough to reconstruct what happened (verified via a fresh `find()` read, not in-memory state).

---

## WP5 — Workspace UI (`ui-shell`) — DONE (with two deferred items, see below)

**Built:**
- `adapter-dummy-runtime` (new module): promotes WP4's test-only `DummyRuntimeProvider` into a real,
  shipped `RuntimeProvider` — still spawns a copy of `ping.exe` under a real Job Object via WP1's
  `WindowsProcessLauncher`, but is now the actual wired bean (`AdapterConfig`), since
  `adapter-rabbitmq` remains a WP6-blocked stub and the workspace UI needs something genuine to
  start/stop. `RuntimeKind.DUMMY` was added to domain-core for it (unused anywhere else, verified
  by grep before adding).
- `WorkspaceControlPort` (`ui-shell`, implemented by `SpringWorkspaceControlPort` in
  `app-bootstrap`) — the same interface-in-`ui-shell`/impl-in-`app-bootstrap` split already used for
  `DiagnosticsSource`. Workspace/instance create/list/delete; start/stop each run as a real one-node
  `OperationPlan` through the WP4 `OperationEngine`, persisting a `LaunchRecord` before reporting
  success (D5 ordering) and running a `Reconciler` pass inline before the node returns, so state is
  correct as soon as the operation completes rather than waiting for the next 30s timer tick.
- New persistence: `RuntimeDefinitionRepository` (insert/findById — only `RuntimeSource.Imported` has
  a persisted encoding today, `Managed`/`System` deferred until a real caller needs them);
  `WorkspaceRepository.findAll`/`delete` (cascades instance/launch_record/pipeline rows — SQLite here
  has no `ON DELETE CASCADE`); `InstanceRepository.delete`.
- `WindowsCurrentUser` (`platform-windows`): a real `Advapi32Util`-backed SID lookup for the
  `OwnerSid` recorded on a new `Workspace` — not a placeholder string.
- `WorkspacesPane`/`ClaudevShell` (`ui-shell`): a second tab alongside the existing diagnostics
  screen — workspace list, instance list with a live-colored state column, start/stop/create/delete,
  and a live operation event log fed by `OperationEngine.subscribe()`. Polls the control port every
  2s on a background thread (SQLite reads are blocking) and applies results via `Platform.runLater`.
  A real `java.awt.SystemTray`/`TrayIcon` (best-effort — `SystemTray.isSupported()` gates it) makes
  window-close hide-to-tray instead of quit, with a generated icon (no bundled asset exists yet, see
  WP9); if the tray isn't available on a given session, close still quits rather than stranding an
  invisible process, matching the original fallback behavior.

**Verified:** 112 tests pass across the reactor (`mvn test`, real SQLite/Win32, not mocked),
including new coverage for the cascading-delete/`findAll` repository methods, the real SID lookup,
and a real spawn/stop round trip through `adapter-dummy-runtime`. `mvn spring-boot:run` starts the
full Spring context (including the new `SpringWorkspaceControlPort` bean) and opens the JavaFX
window with both tabs — confirmed via logs (clean startup, no exceptions) and a real window handle
with the correct title/size; this session's screenshot tooling could not get a rendered frame back
(a white client area) even after minimize/restore, which reads as this specific environment's
remote-display capture, not an application fault — the same limitation would apply equally to the
pre-existing diagnostics-only screen. Clicking through the UI by hand is still worth doing on a
normal desktop session before calling this fully proven.

**Deviations from the original acceptance line, both honest scope cuts, not oversights:**
- The **"not encrypted" badge for legacy-secret-store-backed items (D13)** is not built. Nothing in
  this vertical slice ties an `Instance`/`Workspace` to a `SecretRef` yet — no adapter reads a secret
  through a workspace-visible path (that arrives with WP6/WP7's real connection/credential handling).
  Wiring the badge now would mean inventing a UI-only stand-in for state that doesn't exist yet.
- **"Killing the instance externally flips the UI to `ORPHANED` within one reconcile interval"**
  is true only on the existing 30s `ReconcilerScheduler` timer, not the UI's own 2s poll — an
  external kill isn't detected until that timer's next tick, same as before WP5. The *UI-initiated*
  start/stop path is faster (a reconcile pass runs inline in the same operation), but this session
  did not add a shorter dedicated timer for the externally-killed case; doing so is a one-line change
  to `ReconcilerScheduler`'s `fixedDelay` if the 30s interval proves too slow in practice.

---

## WP6 — RabbitMQ adapter

**Spike A: DONE, passed (2026-09-12)** — see [RABBITMQ_RUNTIME.md](RABBITMQ_RUNTIME.md) for full
results. All four criteria verified against real RabbitMQ 4.3.5 + Erlang/OTP 27.3.4.17 portable
zips: two simultaneous nodes with independent nodename/port/cookie/data-dir; a graceful single-node
stop that didn't disturb the other node's uptime or registration; a pre-existing/external EPMD that
survived both managed nodes stopping and accepted a third node without being respawned; a fully
Turkish-character data/log directory tree that booted, wrote, and stopped without issue. Also
surfaced a real, release-relevant fact: the newest Erlang (OTP-29) cannot boot RabbitMQ 4.3.5 at
all, which is exactly the failure mode `RuntimeSource.Managed`'s pinned-pair design exists to
prevent. **Not yet done:** an equivalent run on a true clean/minimal VM (this spike ran on a dev
box with existing JDK/Maven/git tooling already present) — worth one more pass before shipping, not
a blocker to starting the build below. The adapter itself (the actual `adapter-rabbitmq` build) is
**not started** — this entry only covers the spike that was blocking it.

**Adapter built and verified, DONE — not yet wired into app-bootstrap (see below):**

- `RabbitMqPinnedPair`: the exact `(Erlang/OTP 27.3.4.17, RabbitMQ 4.3.5)` pair the spike proved
  works, with SHA-256 checksums computed against this session's own downloaded bytes (GitHub does
  not publish a checksum for Erlang's Windows zip asset — see the honesty note in
  `RabbitMqPinnedPair`'s javadoc).
- `RabbitMqBinaryProvisioner`: real download (`java.net.http.HttpClient`) + SHA-256 verification +
  zip extraction (with a zip-slip guard — rejects any entry that would land outside the target
  dir) into a managed directory; idempotent (skips network entirely if the pair is already present).
- `EpmdSupervisor`: proactively starts `epmd.exe` as a plain, unsupervised `ProcessBuilder` process
  (never through `WindowsProcessLauncher`/a per-instance Job Object) before any node boots, so
  Erlang finds it already listening and never spawns its own — sidesteps entirely the question of
  whether epmd would inherit a node's Job Object if Erlang spawned it as a child.
- `RabbitMqCtl` + `RabbitMqRuntimeProvider`: the real `RuntimeProvider` — `start` spawns
  `rabbitmq-server.bat` via `cmd.exe /c` (required for a `.bat`, same `ADR-010` reasoning as
  `MavenBuildExecutor`) through WP1's `WindowsProcessLauncher`/Job Object, with per-instance
  nodename/cookie/ports/data-dir all passed as explicit environment variables (never inherited,
  never on the command line), and waits for `rabbitmqctl await_startup` before reporting success
  (a real boot-readiness signal, not port occupancy — a deliberate, better substitute for "the
  management API" this entry originally proposed, since enabling the management plugin would be
  extra scope with no readiness benefit over `await_startup`). `stop` tries a graceful
  `rabbitmqctl stop` first (honoring `StopInstanceCommand.graceful`, the first real use of that
  field in this codebase) and falls back to `Job.terminate()` if that doesn't exit the process in
  time.
- **Port convention** (documented, not encoded in `provider-api`, per D2):
  `StartInstanceCommand.requestedPorts()` must contain exactly two values — the lower becomes the
  AMQP port, the higher the Erlang distribution port.

**Verified for real** (`RabbitMqBinaryProvisionerTest`, `RabbitMqRuntimeProviderTest` — 7 tests,
reusing the spike's cached binaries so they don't re-download ~250MB): checksum-mismatch rejection
and zip-slip rejection against a real local HTTP server and real zips; a full start→healthCheck→
graceful-stop cycle against a real spawned node; **two simultaneous real nodes, stopping one, and
confirming both the other node and EPMD survive — this time through the adapter's own Job Object,
not the spike's plain `Start-Process`**, which is the actual scenario `EpmdSupervisor` exists to
get right; a fully Turkish-character data/log directory end to end.

**A real bug found while testing, not simulated:** the first attempt failed every real spawn with
"No PATH variable (!)" — `rabbitmqctl`/`rabbitmq-server.bat` need ordinary Windows tools on `PATH`
that this codebase's "never inherit the ambient environment" rule (docs/SECURITY.md) had stripped
out entirely. Fixed via `RabbitMqEnvironment`, an explicit allow-list (`PATH`, `PATHEXT`,
`USERPROFILE`, `APPDATA`, `LOCALAPPDATA`, `TEMP`, `TMP`, `ComSpec`, `SystemRoot`) — the same pattern
`CorporateNetworkEnvironment` already uses in `adapter-fe-pipeline`, independently rediscovered here
because adapters don't share code across modules (see docs/REPO_LAYOUT.md's dependency direction).
A second real bug, in the test infrastructure itself: `HttpResponse.BodyHandlers.ofFile(path,
CREATE, TRUNCATE_EXISTING)` silently defaults to a read-only channel open without an explicit
`WRITE` option (per `Files.newByteChannel`'s own contract — `CREATE` requires `WRITE`/`APPEND` to
have any effect), throwing `NoSuchFileException` for a file that was never going to be created.

**A third real bug, a genuine race, found via a flaky test rather than assumed:** immediately after
`WindowsProcessLauncher.launch` resumes the suspended `cmd.exe` (which then execs `erl.exe`), the
node has not necessarily registered with EPMD yet. A single `rabbitmqctl await_startup` call issued
at that instant sometimes got back "node ... not running at all" — EPMD's answer for "I have never
heard of this name" — and `await_startup` returned that as a hard failure immediately rather than
retrying through it, since from its own perspective there was nothing yet to wait on. About 1 start
in 3-4 hit this in practice. Fixed by retrying the whole `await_startup` invocation ourselves (every
500ms, up to the same 60s overall budget) instead of trusting a single call's internal retry
behavior to cover this specific transient state — confirmed fixed over several repeated full runs
after the change, not just one lucky pass.

**Now wired into `app-bootstrap`** (this was originally deferred, then picked up in the same WP6
pass once the adapter itself was proven solid): `RabbitMqProviderHolder` provisions the pinned pair
*lazily*, on first dispatch to a RABBIT_MQ instance, not at Spring context startup — confirmed via a
timed `mvn spring-boot:run` (under 1s to `Started ClaudevApplication`, no network I/O) that adding
this bean doesn't regress every other startup's cost. `SpringWorkspaceControlPort` now dispatches
`start`/`stop` to whichever `RuntimeProvider` actually owns the instance's `RuntimeDefinition.kind`
(`DUMMY` → the WP5 dummy provider, `RABBIT_MQ` → the lazily-provisioned RabbitMQ one) instead of
always using one fixed provider, and `WorkspacesPane` gained a "New RabbitMQ instance..." button
that allocates two real free loopback ports (two simultaneously-open `ServerSocket(0)`s, so the OS
can't hand back the same ephemeral port for both) before creating the instance. A RabbitMQ
instance's first-ever start on a machine logs a note that it may take a while (the ~250MB download),
so the operation log doesn't look stuck. **Not yet clicked through by hand in this session** — see
the same screenshot-capture limitation noted in WP5; verified via the full test suite plus a timed
startup log, not visually.

**Acceptance:** two simultaneous instances under a non-ASCII (Turkish) profile path — met; stopping
one disturbs neither the other nor a pre-existing/EPMD-owning-job scenario — met; "app crash leaves
no orphaned Erlang VM" is a property of `KILL_ON_JOB_CLOSE` already proven generically by WP1's
`WindowsJobObjectSmokeTest`, not re-tested RabbitMQ-specifically (killing this test JVM mid-test to
prove it would mean sacrificing the very process running the test suite).

---

## WP7 — Redis adapter — data plane DONE; authorization/UI still open

**A real architecture gap found before any adapter code, not an implementation detail:**
`ConnectionProvider#scan`/`getString`/`authorizeMutation` all took a bare `connectionId` string
with no host/port/credential anywhere in the interface — unlike `RuntimeProvider`, where every
command DTO already carries what an adapter needs, there was no way for an implementation to know
what to connect to. Fixed by adding an explicit `connect(ConnectOptions)`/`disconnect(connectionId)`
lifecycle to the port — see
[ADR-011](adr/ADR-011-connectionprovider-explicit-connect-lifecycle.md) for the decision and the
rejected alternatives (passing connection details on every call; adapter-owned out-of-band config).

**Built and verified for real** (`RedisConnectionProviderTest`, 10 tests, against a genuine Windows
Redis 5.0.14.1 build — see "Sourcing" below, not mocked):
- `connect`/`disconnect`: a real Lettuce `RedisClient`/`StatefulRedisConnection`, keyed by
  `connectionId` in an internal map (same pattern as `RabbitMqRuntimeProvider`'s `instanceId ->
  handle` map) — connecting to an unreachable host fails cleanly rather than hanging.
- `getString`/`SCAN` paging (real cursor-based `SCAN`, not `KEYS` — the "possibly-incomplete live
  view" property from [REDIS_SCOPE.md](REDIS_SCOPE.md) is real: keys inserted mid-scan are still
  found by continuing to page).
- The String and Key/TTL rows of the typed edit table: `SET`/`APPEND`/`DEL`/`EXPIRE`/`PERSIST`
  through `authorizeMutation`, dispatched by an allow-list `switch` — anything not in it (tested
  with `FLUSHALL`) is rejected, by construction, not by a UI-layer hint.
  `EVAL`/`CONFIG`/`CLUSTER`/a raw console were never given a code path to reach in the first
  place, matching the "excluded from V1" list, not merely omitted from a menu.
- A real bulk pattern-`DEL` preview/commit flow: `prepareMutation` `SCAN`s with `MATCH` to compute
  the exact matched key set and issues a token; `commitMutation` re-scans and rejects
  (`ProviderError.Conflict`) if the matched set changed since preview — verified by a test that
  inserts a new matching key between preview and commit and confirms the commit is refused, and a
  second test confirming a token is single-use (a second `commitMutation` with the same token fails
  because it's already been consumed, whether or not it "expired").
- Every operation against a `connectionId` that was never `connect`ed returns `NotFound`, not a
  null/empty/silent result.

**Hash/List/Set/ZSet — DONE** (picked up as backlog item 4, using WP7's own concrete follow-up
note): [ADR-012](adr/ADR-012-redis-typed-edit-set-dto-shape.md) added `MutationRequest.field`
(`Optional<String>`, meaning depends on `operation` — hash field name for `HSET`/`HDEL`, score for
`ZADD`) via a source-compatible overload (the old 4-arg constructor still works, defaulting `field`
to empty), plus four new `ConnectionProvider` read methods: `getHash` (size-capped, not a live
cursor), `getListRange`/`getSortedSetRange` (genuinely bounded by a caller-supplied index window,
like `LRANGE`/`ZRANGE` themselves), `getSetMembers` (size-capped). `authorizeMutation`'s allow-list
switch grew `HSET`/`HDEL`/`LPUSH`/`RPUSH`/`LPOP`/`RPOP`/`SADD`/`SREM`/`ZADD`/`ZREM`, each real
against the same test Redis build — `HSET` without a field, or `ZADD` with a non-numeric score, are
rejected before reaching Lettuce. Verified by 6 new tests in `RedisConnectionProviderTest` (16
total) exercising every new operation and its read-back for real, including the two rejection
cases. `ADR-012` also records why a single generic `getCollection` method or full `HSCAN`/`SSCAN`/
`ZSCAN` cursor paging were considered and not built — see the ADR for the actual reasoning, not
repeated here.

**Still not built:** `environmentClass` gating/audit-entry writing and any UI (connection creation,
browsing, the mutation-confirmation flow) — this WP delivers the adapter's full data-plane, not the
application layer around it (matching how WP6 separated "adapter built" from "wired into the UI").

**Sourcing for testing, not production:** `adapter-redis` never downloads or manages a Redis binary
— docs/REDIS_SCOPE.md's "no managed local Redis" rule is a real constraint honored here, not
routed around. The real Redis server the tests run against is a genuine Windows build of upstream
Redis 5.0.14.1 from the `tporadowski/redis` community fork (the same one referenced in
REDIS_SCOPE.md as an example of what *not* to bundle), downloaded to `D:\dev\workspace\claudev-spike`
purely as test infrastructure — exactly the same relationship `GitStepsIntegrationTest` has with a
real public GitHub repo, not a step toward shipping it.

**Acceptance (from the original entry, status per item):** "a scripted single-key mutation against
an unlocked-but-unclassified connection is refused by policy" — not testable yet, no
`environmentClass`/authorization-gate layer exists above `authorizeMutation` (the port method name
is aspirational; today it just performs the operation). "A glob delete without a completed token
round trip is refused" — met. "The token is rejected when underlying data changed since preview,
not only on expiry" — met.

---

## WP8 — FE pipeline adapter — DONE

Picked up ahead of WP6/WP7 because it's the one real adapter fully testable with tools already on
this machine (git, Maven, a JDK-builtin HTTP server) — no Redis/Docker/RabbitMQ+Erlang available
here, and WP6 has its own feasibility spike to run first regardless (see RABBITMQ_RUNTIME.md).

**Built:**
- `adapter-fe-pipeline`: all ten steps from the catalog (`EnsureCheckout`, `GitFetch`,
  `GitFastForward`, `MavenBuild`, `ConfigPatch`, `StageArtifact`, `AtomicDeploy`, `StartProcess`,
  `HealthCheck`, `ExecStep`), dispatched by `FePipelineProvider` from a fixed registry —
  sequential, fail-fast, never compiled per-project code.
- `Params` — typed, clearly-failing extraction from a `StepInvocation`'s generic
  `Map<String, Object>`, the closest V1 gets to "validated against a schema owned by the step
  type" without a full JSON-schema layer.
- `ExecutableLocator` — resolves `git.exe`/`mvn.cmd`/`cmd.exe` to a validated absolute path (PATH
  search, or an env-var override for import/testing), never left to implicit PATH search at spawn
  time.
- `CliProcessRunner` — the shared spawn-wait-capture path every CLI-shaped step uses: WP1's
  `WindowsProcessLauncher`, argv as a list, output captured to temp files so a failure message is
  actually readable rather than just an exit code.
- `CorporateNetworkEnvironment` — the explicit, named allow-list of environment variables forwarded
  to Git/Maven spawns (proxy/CA vars per the corporate-network NFR, plus `JAVA_HOME` — a real gap
  found while testing, see below). Nothing is inherited wholesale.
- **New in `platform-windows`, built for this**: `ProcessExitWaiter` (waits for a spawned CLI
  process to exit and returns its exit code, with the same pid+creation-time re-verification
  discipline as everywhere else — never wait on an unverified pid); `LaunchSpec` gained optional
  `stdoutFile`/`stderrFile` redirection (`CreateFileW` + inheritable `SECURITY_ATTRIBUTES` +
  `STARTF_USESTDHANDLES`, opt-in, the pre-existing 6-arg constructor and its behavior are
  unchanged for every existing caller).
- `MavenGoalValidator` + [ADR-010](adr/ADR-010-maven-goal-allowlist-not-cmd-quoting.md): `mvn.cmd`
  is a batch script and can only run via `cmd.exe /c`, which has its own, genuinely
  hard-to-fully-neutralize metacharacter/`%`-expansion parsing separate from `CreateProcessW`'s
  argv rules. Rather than build and adversarially test a second quoting layer, every goal/phase is
  validated against a strict allow-list before it ever reaches `cmd.exe`.

**Verified (real git.exe against a real public GitHub repo, a real local Maven build, a real local
HTTP server — nothing mocked):**
- Clone into a directory whose path contains a space (the same argv-quoting concern as always, now
  through the actual Git step path, not just WP1's launcher in isolation); a second `EnsureCheckout`
  call is a true no-op.
- `GitFastForward` blocks on a dirty worktree (checked explicitly, before attempting anything) and
  on a genuinely diverged local commit (`--ff-only` refuses); succeeds when actually behind the
  remote, landing exactly on the remote's latest commit.
- `MavenBuild` runs a real goal against a trivial project; a failing goal produces a
  `StepExecutionException` whose message contains real captured Maven output; an unsafe goal string
  is rejected before anything spawns.
- `ExecStep` runs a real allow-listed executable (including one copied to a path with a space in
  it), fails cleanly on a non-zero exit, and rejects a relative or nonexistent executable path
  outright.
- `HealthCheck` polls a real local server through initial failures to a real success, and correctly
  times out against both a server that never turns healthy and nothing listening at all.
- `FePipelineProviderTest`: a real two-step pipeline (checkout + stage) succeeds end to end; a
  failing first step stops the pipeline before a later step ever runs; an unknown step type fails
  without running anything.

**A real bug found while testing, not guessed:** the first "real Maven build" test failed with
`mvn.cmd`'s own `"JAVA_HOME environment variable is not defined correctly"` — because this
codebase's spawns are deliberately fully explicit about environment (docs/SECURITY.md), and
`JAVA_HOME` wasn't yet in the forwarded allow-list. Fixed by adding it (and `M2_HOME`/
`MAVEN_HOME`/`MAVEN_OPTS`) to `CorporateNetworkEnvironment`; confirmed `PATH` itself was *not*
also needed once `JAVA_HOME` was present, so it was deliberately left out rather than added
"just in case."

**Deviation from the original acceptance line, documented rather than silently substituted:** WP8
was originally scoped as "a test Bitbucket repo and Maven project behind a proxy/CA." No Bitbucket
instance or corporate proxy/CA exists to test against in this environment, so a small public
GitHub repo (`octocat/Hello-World`) stands in for the real-repo requirement, and the proxy/CA
forwarding exists in `CorporateNetworkEnvironment` but is **not yet verified against a real proxy**
— tracked as open in docs/RISK_REGISTER.md. "Pre-execution argv is logged and inspectable" is also
not yet built (no logging of the constructed argv before spawn) — a straightforward addition, not
done here to keep this pass focused on the step logic itself being real and correct.

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

## WP10 — RabbitMQ/Redis operational extras

Added after a user question about two capabilities neither adapter had: pointing the app at an
already-installed Redis/RabbitMQ instead of the app-managed path, and RabbitMQ plugin management
(e.g. Shovel).

**10a — Redis: manual connection UI — DONE.** `RedisConnectionProvider.connect(ConnectOptions)`
already did this for real (WP7); this built the UI and the persistence around it. **Built:** a
`ConnectionRepository` (V1 scope: `ConnectionKind.Remote` only, a fixed `RedisSafetyPolicy
.restrictiveDefault()` — see its own javadoc); `ConnectionControlPort` (`ui-shell`)/
`SpringConnectionControlPort` (`app-bootstrap`), mirroring the `WorkspaceControlPort` interface-in-
`ui-shell`/impl-in-`app-bootstrap` split; a password, if given, is stored via the real DPAPI
`SecretStore` (never persisted as plaintext) and re-resolved transparently on first use after a
restart (`ensureConnected`, tracking which connection ids this JVM run has actually opened, since
the underlying Lettuce connection is memory-only); a new top-level "Connections" tab in
`ClaudevShell`/`ConnectionsPane` (connections are global, not workspace-scoped, per `Connection`'s
own domain shape) — connect dialog, connection list, `SCAN`-paged key browser, value viewer.

**Verified for real** (`ConnectionRepositoryTest` + `SpringConnectionControlPortTest`, against the
same genuine Windows Redis build WP7 uses): a connection round-trips through real SQLite with and
without a secret; connecting to an unreachable address throws and persists nothing (no orphaned
row for a connection that never actually worked); a password round-trips through a real DPAPI
store and is correctly re-resolved and re-authenticated by a **separate** `SpringConnectionControlPort`
instance sharing only the persisted repository — simulating an app restart — against a real
password-`requirepass`d Redis instance; delete closes the real connection and removes the row;
`SCAN` paging and `getString` work through the full port stack, not just the adapter directly.

**A real bug found while testing, not assumed:** the first password-round-trip test pointed a
password at a Redis instance that had none configured — Lettuce's connect handshake sends an
unsolicited `AUTH`, which a real server without `requirepass` rejects, failing the *whole* `connect()`
with a generic "Unable to connect" rather than a clear auth error. Not a code bug (this is correct,
expected Redis/Lettuce behavior), but a test-design bug: fixed by giving that test its own real
`--requirepass`-configured server rather than asserting a passwordless one would accept one.

**Acceptance:** connecting to the real Windows Redis test build from the UI, browsing its keys via
`SCAN` paging, and reading a value back — verified via the full test suite driving the same
`ConnectionControlPort` the UI calls; not yet clicked through by hand (same screenshot-capture
limitation noted since WP5).

**10b — RabbitMQ: `RuntimeSource.Imported` support — DONE (config property, not a UI screen).** The
domain type already modeled "user pointed the app at an install directory they manage themselves";
this wired it up. **Built:** `RabbitMqRuntimeProvider.fromImported(erlangHome, rabbitmqSbin)` — a
new public factory (validates `erl.exe`/`rabbitmq-server.bat` exist, matching `ExecutableLocator`'s
validate-before-spawn discipline in `adapter-fe-pipeline`, since `RabbitMqInstallation` itself is
package-private and this is the only way to build a working provider from arbitrary paths outside
`adapter-rabbitmq`); `RabbitMqProviderHolder` now takes two optional properties
(`claudev.rabbitmq.imported-erlang-home`/`-imported-rabbitmq-sbin`) — when both are set, `.get()`
uses them directly (no download, no checksum, no network at all); when unset, the existing
`.provision()` pinned-pair path runs unchanged. A misconfigured imported path fails loudly on first
use rather than silently falling back to downloading — a deliberate choice, since silently ignoring
a path a user set on purpose would be a worse failure mode than an obvious error.

**Verified for real**: `RabbitMqRuntimeProviderFromImportedTest` (a missing `erl.exe`/
`rabbitmq-server.bat` is rejected with a clear message; a valid imported pair actually spawns and
stops a real node); `RabbitMqProviderHolderTest` proves the imported branch — not the fallback —
actually ran, by giving the holder a deliberately nonexistent `managedDir` fallback: if `.get()`
had fallen through to `.provision()`, it would have failed against that bogus path, so a successful
`.get()` is proof the imported paths were used.

**Explicitly out of scope, not a smaller version of this item:** attaching to a RabbitMQ node the
user starts and keeps running independently of this app. `RuntimeProvider`'s contract is "this app
spawns and owns the process lifecycle" (Job Object, `start`/`stop`) — there is no "attach to an
existing PID" shape in the port, and adding one would be a `RuntimeProvider`-wide port change, not a
RabbitMQ-specific tweak. If that capability is wanted later, treat it as its own ADR-worthy
decision, not folded in here. **Also not built:** any UI to set these two properties from within
the app (today they're `application.properties`/JVM-arg only) — a real, small follow-up, not
required for the underlying capability to work.

**10c — RabbitMQ: plugin management (Shovel, management, federation, ...) — DONE.**

**Design decision made deliberately, not bolted on**: plugin methods
(`listEnabledPlugins`/`enablePlugin`/`disablePlugin`) were added as **public methods directly on
`RabbitMqRuntimeProvider`**, not on the shared `RuntimeProvider` port — no other runtime kind has a
"plugins" concept, and `provider-api` stays domain-agnostic (D2). `SpringWorkspaceControlPort`
resolves the instance's provider as usual, then `instanceof`-checks for `RabbitMqRuntimeProvider`
before delegating, rejecting non-RabbitMQ instances with a clear message rather than silently
no-op'ing.

**Built:**
- `RabbitMqPlugins`: spawns `rabbitmq-plugins.bat list/enable/disable` via `cmd.exe /c` through
  WP1's launcher — same shape as `RabbitMqCtl`, which was generalized (`runScript`) to spawn any
  `rabbitmqctl`-family script rather than duplicating the spawn/capture glue a second time.
- `RabbitMqRuntimeProvider.listEnabledPlugins`/`enablePlugin`/`disablePlugin`: work only for an
  instance tracked in the provider's in-memory `nodes` map (same pre-existing limitation as
  `start`/`stop` — an instance whose node survived an app restart but was never re-started in this
  JVM run can't have its plugins managed until it is).
- `WorkspaceControlPort` (`ui-shell`) gained the same three methods; `WorkspacesPane` gained a
  "Plugins..." dialog (enabled-plugins list, a name field, Enable/Disable/Refresh).

**A real CLI quirk found by exploring the actual binary, not assumed:** `rabbitmq-plugins.bat
enable <nonexistent-name>` exits **0** — it prints a WARNING, not an error, so the exit code alone
cannot tell you whether a plugin was actually enabled. `enable`/`disable` always re-check
`listEnabled` afterward and report success based on the plugin's *actual* presence/absence in that
list, never the exit code.

**Verified for real** (`RabbitMqRuntimeProviderPluginsTest`, `SpringWorkspaceControlPortPluginsTest`
— 5 tests): enabling/disabling the real `rabbitmq_shovel` plugin on a real running node actually
changes its state (checked via a fresh `list -e -m` read, not assumed from the enable call); a
nonexistent plugin name is correctly reported as a failure despite the CLI's zero exit code; plugin
actions against an untracked instance id are rejected; the full path — create a RabbitMQ instance
through `SpringWorkspaceControlPort`, start it for real (reusing WP10b's imported-path mode against
the spike's cached binaries), then enable/disable a real plugin through the same port the UI calls —
works end to end; the same call against a `DUMMY` instance is rejected with a clear message instead
of silently doing nothing.

**Acceptance:** enabling Shovel on a real running node — met (verified via the full test suite
driving the same port the UI calls, not yet clicked through by hand — same screenshot-capture
limitation noted since WP5). "A real shovel actually moving a message between two real queues" was
not attempted — out of scope for "can this app enable/disable a plugin," which is what 10c set out
to answer; that would be its own follow-up exercising Shovel's actual message-routing feature, not
plugin management.

---

## Remaining backlog, replanned and prioritized (post-WP7)

Supersedes scattered "not done"/"deferred" notes above as the actual next-up order — those notes
stay as the historical record of what each WP decided to skip and why; this list is where to start.
10a/10b/10c are done (see above) and stay listed only so the sequence reads as it was actually planned.

1. ~~**10a — Redis manual connection UI.**~~ DONE.
2. ~~**10b — RabbitMQ `Imported` source support.**~~ DONE as a config property; the UI to set it
   from within the app is still open (see 10b's own note) but doesn't block the capability.
3. ~~**10c — RabbitMQ plugin management.**~~ DONE — a RabbitMQ-specific escape hatch on
   `RabbitMqRuntimeProvider`, not a `RuntimeProvider` port method (see 10c's own note on why).
4. ~~**Redis Hash/List/Set/ZSet typed edit set.**~~ DONE — see [ADR-012](adr/ADR-012-redis-typed-edit-set-dto-shape.md).
5. **Redis `environmentClass` authorization + audit-entry gate.** The safety layer `authorizeMutation`
   is currently named for but doesn't yet enforce — docs/REDIS_SCOPE.md's core safety property.
6. **WP9 — packaging.** jlink/jpackage, code signing, licensing/SBOM gate, log rotation/redaction,
   the bounded graceful-shutdown DAG. Large, and blocked in practice on RISK_REGISTER's still-open
   jlink/jpackage no-admin-install spike.
7. **RISK_REGISTER cleanup items**, roughly independent of the above and each other: a true clean-VM
   run of the WP6 RabbitMQ spike (this session's spike ran on an already-tooled dev box); pre-spawn
   argv logging (flagged since WP8); `ExecStep`'s per-workspace allow-list config surface (currently
   only checks the path is absolute and exists); `MAX_PATH`/`\\?\` long-path support.

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
