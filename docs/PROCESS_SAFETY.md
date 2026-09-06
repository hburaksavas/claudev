# Process & Job Safety Model

## Identity, never PID alone

Windows recycles PIDs quickly under load. Every kill/signal action verifies, in order:

1. Does a process with the recorded `pid` exist?
2. Does its `processCreationTime` match the stored `LaunchRecord`?
3. Does its image path/fingerprint (`exeFingerprintSha256`) match?
4. Does an adapter-specific health probe succeed (RabbitMQ management API `/api/overview`, Redis
   `PING`, ...)?

Any mismatch downgrades the instance to `ORPHANED` or `UNKNOWN` — never `RUNNING`, and never
signaled.

## Job Object topology — one per component, not one per workspace

**Decision:** one Job Object per long-lived managed component process tree, and one per
short-lived operation process tree (Git/Maven/etc.). **No workspace-parent Job Object.** See
[adr/ADR-002-job-topology-no-workspace-parent.md](adr/ADR-002-job-topology-no-workspace-parent.md)
for the full argument (a parent job's only real value — a single-syscall "kill everything" — isn't
worth the added nesting/assignment-failure risk on a machine where an external tool, e.g.
AppLocker/EDR, may already have wrapped the app's own process in a restrictive job).

"Force Stop Workspace" is a parallel fan-out of `TerminateJobObject` over every component job in
the workspace — see `WindowsJobObject.terminate` in `platform-windows` — not a parent-job
primitive.

### The compensating invariant (binding, not optional)

Per-component jobs are a strictly weaker crash-safety guarantee than a parent job **unless** every
spawn path follows this exact ordering:

1. `CreateProcess` **suspended**.
2. Create the component's Job Object with `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE` set.
3. `AssignProcessToJobObject` — verify success.
4. **Only then** resume the thread.
5. Write the `LaunchRecord` to SQLite **synchronously, before** the spawn call reports success to
   any caller — a crash between "process running" and "record durably saved" must be impossible by
   construction, not by hope.

A startup reconciler pass explicitly diffs the OS process table (any process whose image path
resolves under the app's managed-binaries directory) against the set of `LaunchRecord`s. Anything
running-but-unrecorded is `UNTRACKED` — a bug signal, not a legitimate orphan.

`platform-windows`'s `WindowsJobObject` wraps the raw Win32 calls (verified against a real spawned
process on a live machine — see `WindowsJobObjectSmokeTest`) but does **not** itself enforce this
ordering; callers (the eventual `operation-engine`/adapter spawn code) must.

## Reconciler states

```
STOPPED | STARTING | RUNNING | DEGRADED | STOPPING | ORPHANED | UNKNOWN | UNTRACKED
```

Runs at app startup — mandatory, blocking the UI until the first pass completes — and on a timer
thereafter. A stored `RUNNING` status is never trusted at face value.

## Managed-process lifetime

Managed processes are supervisor-owned and do not survive application exit. Window close hides to
tray (`Platform.setImplicitExit(false)`); an explicit Quit runs a graceful Stop All through the
operation engine, waits for it to settle, then exits. A JVM crash closes every Job Object handle
the process held, which kills every owned tree via `KILL_ON_JOB_CLOSE` — the same guarantee, with
no second process's lifecycle to reason about (no Electron sidecar — see
[adr/ADR-009-javafx-single-jvm-not-electron.md](adr/ADR-009-javafx-single-jvm-not-electron.md)).
On relaunch, the reconciler expects zero owned survivors; anything present is `ORPHANED`/`UNKNOWN`
and is never auto-adopted or killed without provider-specific identity proof.

## Windows pitfalls catalogue

- **PID reuse** — see Identity section above.
- **Job Object breakaway** — verify `JOB_OBJECT_LIMIT_BREAKAWAY_OK`/`SILENT_BREAKAWAY_OK` are not
  set on any job in the chain; a fast-starting child (or grandchild) can otherwise escape before
  being captured.
- **Maven → JVM chains** — `mvn.cmd` wraps `cmd.exe`, which spawns the real `java.exe`. A
  single-level "track my direct child" scheme loses the JVM entirely; the Job Object (not PID
  tracking) is what makes this safe.
- **Port occupancy vs. exclusion ranges** — "is the port free" must be a real bind attempt, not a
  listen scan; Windows reserves ephemeral/excluded ranges that can make a port unbindable with
  nothing shown listening on it.
- **File sharing/locking** — Windows takes exclusive locks by default; log-tailing while the
  producing process still holds the file needs `FILE_SHARE_READ | FILE_SHARE_WRITE` on open.
- **AV/EDR interference** — freshly-written/extracted binaries can be transiently locked by
  real-time scanning; retry-with-backoff, don't hard-fail on first `ERROR_SHARING_VIOLATION`.
- **MAX_PATH** — declare `longPathAware` in the app manifest; use `\\?\`-prefixed paths for
  anything the app constructs.
- **RabbitMQ node identity** — see [RABBITMQ_RUNTIME.md](RABBITMQ_RUNTIME.md).
- **Unsigned binaries / SmartScreen** — expect friction on a managed corporate fleet; the app's
  own installer/executable should be code-signed at minimum.
