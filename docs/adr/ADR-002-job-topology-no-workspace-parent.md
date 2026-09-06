# ADR-002: One Job Object per component/operation, no workspace-parent Job Object

## Status
Decided, with a binding compensating invariant (see Consequences).

## Context
Windows Job Objects with `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE` are how this app guarantees a
managed process tree (RabbitMQ, a Maven build, ...) dies when the app no longer wants it alive,
including on a crash. The question is topology: one Job Object per workspace (nesting every
component underneath it), or one Job Object per component/operation with no parent.

## Decision
One Job Object per long-lived managed component process tree, and one per short-lived operation
process tree. **No workspace-parent Job Object.**

## Alternatives rejected
- **One parent Job Object per workspace, components nested inside.** Its only real value is a
  single-syscall "kill everything in this workspace" — but `Stop All` is already an orchestrated
  loop over components regardless (graceful shutdown attempts before a hard kill), so atomic
  bulk-kill isn't actually the operation you want most of the time. The real cost: this product
  targets managed corporate Windows fleets where AppLocker/EDR/Group-Policy tooling routinely
  job-wraps processes already. Adding the app's *own* nested parent job on top of a possibly
  already-restrictive external job is exactly the kind of interaction most likely to fail in ways
  that are hard to reproduce and debug (job nesting/assignment failure modes).

## Consequences
- "Force Stop Workspace" is implemented as a **parallel fan-out** of `TerminateJobObject` over
  every component job in the workspace, not a single call on a parent job. A few milliseconds
  slower, none of the nesting risk.
- Per-component jobs are a *strictly weaker* crash-safety guarantee than a parent job unless every
  spawn path follows the ordering: suspend → create job with `KILL_ON_JOB_CLOSE` → assign → verify
  → resume, **and** the `LaunchRecord` is written to SQLite synchronously before the spawn call
  reports success. This ordering is binding, not optional — see
  [PROCESS_SAFETY.md](../PROCESS_SAFETY.md).
- The reconciler must distinguish `UNTRACKED` (a process under the app's managed-binaries
  directory with no matching `LaunchRecord` — a bookkeeping bug) from `ORPHANED` (a recorded
  process whose identity/health no longer verifies), so a violation of the above ordering is
  visible as a bug signal rather than silently absorbed into normal reconciliation.
- Not yet validated: behavior when the app's own process is already inside an externally-imposed,
  possibly-restrictive Job Object. Tracked as a spike in [RISK_REGISTER.md](../RISK_REGISTER.md).
