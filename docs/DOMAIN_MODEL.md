# Domain Model

All types below live in `domain-core` as immutable Java records/sealed interfaces — pure Java, no
I/O, no Spring annotations, no dependency on `provider-api`'s wire DTOs (see
[PLUGIN_CONTRACT.md](PLUGIN_CONTRACT.md) for why the two are deliberately separate).

## Workspace

```
Workspace { id, name, createdAt, ownerSid, instanceIds[], pipelineIds[] }
```

`ownerSid` is the Windows SID of the user who created it. A second Windows account opening the
same on-disk store is refused rather than silently taking control of another user's running
instances — see [adr/ADR-006-single-user-v1.md](adr/ADR-006-single-user-v1.md).

## Runtime / Instance

```
RuntimeDefinition { id, kind: RABBIT_MQ|REDIS, source, configSchemaVersion }

RuntimeSource = Managed(version, erlangVersion, checksum)   // RabbitMQ only in V1
              | Imported(installPath)
              | System(discoveredPath)

Instance {
  id, workspaceId, runtimeDefinitionId, name,
  ports: PortSet(requested, bound),
  dataDir, logDir,
  desiredState: STARTED|STOPPED,
  launchRecord: LaunchRecord?,
  observedState: InstanceState
}

InstanceState = STOPPED | STARTING | RUNNING | DEGRADED | STOPPING
              | ORPHANED | UNKNOWN | UNTRACKED
```

`UNTRACKED` (as opposed to `ORPHANED`) means: a process resolving under the app's own
managed-binaries directory is running, but no `LaunchRecord` accounts for it. That's a bookkeeping
bug signal, not a legitimate orphan — see [PROCESS_SAFETY.md](PROCESS_SAFETY.md).

## LaunchRecord — the identity contract

```
LaunchRecord {
  pid, exePath, exeFingerprintSha256,
  processCreationTime,        // Windows FILETIME, not a wall-clock guess
  instanceToken,               // injected via env var, echoed back by health probes that support it
  jobHandleId, workingDir, commandHash
}
```

No kill/signal action fires against a PID without verifying `processCreationTime` and
`exeFingerprintSha256` first. Windows recycles PIDs quickly under load; a stale record pointing at
a since-exited process could otherwise kill an unrelated one.

## Connection (Redis)

```
Connection { id, kind: Local(instanceId)|Remote(host, port, tls), environmentClass, secretRef?, policy }

EnvironmentClass = DEV | STAGING | PROD | UNKNOWN   // UNKNOWN is treated as PROD-restrictive, never permissive

RedisSafetyPolicy {
  readOnlyDefault, commandAllowList, commandDenyList,
  typedConfirmationRequiredFor, keyNamespaceRestriction, auditRequired
}
```

See [REDIS_SCOPE.md](REDIS_SCOPE.md) for exactly which operations require which gate.

## Pipeline (corporate FE)

```
Pipeline { id, workspaceId, steps: StepDefinition[] }

StepDefinition = EnsureCheckout | GitFetch | GitFastForward | MavenBuild
               | ConfigPatch | StageArtifact | AtomicDeploy | StartProcess
               | HealthCheck | ExecStep
```

`GitFetch`/`GitFastForward` are deliberately separate steps (not a combined "GitPull") so a dirty
or non-fast-forward worktree blocks explicitly. `ExecStep` is the sole escape hatch — see
[FE_PIPELINE_STEPS.md](FE_PIPELINE_STEPS.md).

## Operation

```
Operation { id, kind, targets[], dag: OperationDag, status, events[], startedAt, endedAt?, cancelToken }

OperationStatus = PENDING | RUNNING | PARTIALLY_FAILED | FAILED | SUCCEEDED | CANCELLED
```

`PARTIALLY_FAILED` is the expected terminal state for a workspace-wide DAG where one branch
failed — a failed node skips only its own dependents. No transactionality is claimed over external
side effects (a completed `git pull` cannot be "rolled back").

## Secrets & audit

```
SecretRef { id, storeAdapter, opaqueHandle }   // never the plaintext, in SQLite/logs/plans
AuditEntry { id, actor, action, target, timestamp, result, detail }   // append-only
```
