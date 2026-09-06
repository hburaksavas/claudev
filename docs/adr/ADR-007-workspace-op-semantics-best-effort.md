# ADR-007: Workspace operations are best-effort per branch, not all-or-nothing

## Status
Decided.

## Context
`Start All`/`Update All`/`Stop All`/`Restart All` operate over a workspace's instances and
pipelines as a dependency DAG. When one node fails, the engine could either roll back everything
attempted so far, or continue independent branches and report what happened.

## Decision
Best-effort per independent DAG branch: a failed node skips only its own dependents; unrelated
branches continue. The terminal status is `PARTIALLY_FAILED` with a structured summary. No
transactionality is claimed over external side effects (Git, build, deploy). Compensation
(rolling back the engine's *own* state transitions — e.g. marking a not-yet-started dependent back
to `Stopped`) is allowed only where explicitly declared and safe.

## Alternatives rejected
- **All-or-nothing with rollback.** Rollback is well-defined for the engine's own bookkeeping, but
  not for the actions it orchestrates: you can stop a process you started, but "undo a completed
  `git pull`" or "undo a partially-applied Maven build" is not a well-formed operation. Promising
  rollback here would be promising something the implementation cannot actually deliver.

## Consequences
- Callers (UI, scripts) must handle `PARTIALLY_FAILED` as a normal, expected terminal state, not an
  edge case — with a summary that correctly attributes which nodes were skipped as *dependents of*
  the failure, not unrelated branches.
- This is the semantic the M3 acceptance gate directly tests: a deliberately failing node's summary
  must list only its own dependents as skipped.
