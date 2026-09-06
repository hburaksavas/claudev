# Milestones

## M0 — Safety core (no real runtimes)

**Backlog:** SQLite schema + WAL/migrations (real `DataSource` wiring into `persistence`); domain
aggregate revisioning; `operation-engine`'s actual DAG scheduler, cancellation, retry, event bus;
job-per-component/job-per-operation spawn plumbing implementing the D5 ordering invariant on top
of `platform-windows`'s `WindowsJobObject`; reconciler with the full
`Stopped/Starting/Running/Degraded/Stopping/Orphaned/Unknown/Untracked` state machine; a dummy
`RuntimeProvider` that spawns a trivial long-running test binary (to exercise the above without a
real RabbitMQ/Redis dependency).

**Acceptance gate:** hard-kill the app process externally with several dummy instances running; on
relaunch, the reconciler correctly classifies every process — no false `RUNNING`, no false
`ORPHANED` for genuinely-dead entries, any unrecorded-but-running dummy process surfaces as
`UNTRACKED`. No kill/signal action ever fires against a PID whose creation-time+fingerprint
doesn't match.

## M1 — RabbitMQ + Redis connections + secrets

**Backlog:** `adapter-rabbitmq` real implementation (managed source, one pinned pair); EPMD
ref-counted lifecycle; `adapter-redis` real implementation (remote/imported/system only); the base
Redis authorization+audit gate (no preview/token yet, since no bulk ops ship in M1).

**Blocking spike:** RabbitMQ+Erlang two-node feasibility (see [RABBITMQ_RUNTIME.md](RABBITMQ_RUNTIME.md)).

**Acceptance gate:** two simultaneous managed RabbitMQ instances start/stop cleanly, including
under a non-ASCII (Turkish) Windows user profile path; stopping one never disturbs the other or a
pre-existing external EPMD; a remote Redis connection with unset `environmentClass` refuses any
mutation by default; no secret is ever readable from the SQLite file directly.

## M2 — Redis typed edit set + FE pipeline

**Backlog:** the full [REDIS_SCOPE.md](REDIS_SCOPE.md) operation set with the D8/D9 authorization
matrix wired end to end (including `prepareMutation`/`commitMutation` for bulk/glob ops);
`adapter-fe-pipeline` real implementation of the full step catalog plus `ExecStep`; `ConfigPatch`
schema-validated substitution.

**Blocking spike:** Windows argv-quoting/injection adversarial test (see
[TESTING_STRATEGY.md](TESTING_STRATEGY.md)).

**Acceptance gate:** a scripted attempt to mutate a single key on an unlocked-but-unclassified
connection is rejected; a scripted attempt to run a glob delete without completing the token round
trip is rejected; an `ExecStep`/Git-step invocation with a crafted argument (embedded quotes, fake
project name with quotes/backslashes) does not achieve command injection, verified against real
`CreateProcessW`/`ProcessBuilder` behavior.

## M3 — Workspace orchestration + packaging

**Backlog:** Start All/Stop All/Update All/Restart All as DAG operations with `PARTIALLY_FAILED`
summaries; log rotation/redaction (own ADR — see [RISK_REGISTER.md](RISK_REGISTER.md)); the
graceful-shutdown "bounded shutdown" behavior in `ClaudevApplication.gracefulShutdown` (currently a
`TODO`); jlink/jpackage installer (code-signed); first-run licensing/SBOM check for the bundled
RabbitMQ+Erlang pair.

**Acceptance gate:** a workspace with a deliberately failing node produces `PARTIALLY_FAILED` with
a summary listing only that node's dependents as skipped, not unrelated branches; the app installs
and runs fully without admin rights on a clean VM; the licensing/SBOM check blocks a release build
that can't attest the bundled pair's licenses.

## Deferred past M3 (explicit non-blockers)

Redis arbitrary CLI/console, out-of-process plugin protocol, dashboards, marketplace, cloud/team
sync.
