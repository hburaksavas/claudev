# Security Model

## SecretStore

Default adapter (`secret-store-dpapi`) wraps user-scoped Windows DPAPI
(`CryptProtectData`/`CryptUnprotectData`). SQLite only ever stores an opaque `SecretRef`; the
`resolve()` call is the only place plaintext exists, and callers must not persist its return value
anywhere — not in SQLite, not in a log, not in an immutable operation plan.

`secret-store-legacy` is Base64 encoding **only** — not encryption — and exists solely as an
import path for old configs. Off by default; any instance/connection backed by it must show a
persistent (non-dismissible-once) "not encrypted" UI badge, because a one-time toast is easy to
miss and the risk is ongoing.

Both are verified for real (not mocked) — see `DpapiSecretStoreTest` and
`LegacyEncodedSecretStoreTest`.

## Remote Redis destructive-operation guard

A policy model, not a confirmation popup:

- `environmentClass` is required with no silent default — **unset is treated as
  production-equivalent restrictive**, never permissive.
- `readOnlyDefault: true` unless explicitly unlocked per connection.
- **Every** mutation, single-key or bulk, passes the base authorization+audit gate — there is no
  "it's just one key" exemption. Only bulk/glob/flush-class operations additionally require the
  `prepareMutation` → bounded impact preview + short-lived single-use token → typed confirmation →
  `commitMutation` round trip (impact preview is meaningless for a single key the user already
  typed). See [REDIS_SCOPE.md](REDIS_SCOPE.md).
- Every destructive command writes an `AuditEntry` **before** executing, not just after.
- Namespace policy and TLS validation cannot be bypassed from an alternate UI surface.

## Path/argument validation — centralized, not per-adapter

One set of `validatePath`/`validatePort`/`validateUrl`/`validateEnvKey` functions in the core,
used by every adapter and by `ExecStep`. No adapter re-implements its own sanitization.

## `ExecStep` — the one escape hatch, constrained

- `argv` is a plain list passed directly to process creation — **never** a shell string, never
  routed through `cmd /c "<string>"`.
- The executable must resolve to an allow-listed, canonicalized path configured per workspace.
- The environment block is explicitly enumerated, not inherited wholesale from the app's own
  process — the app may be holding decrypted secrets in memory for unrelated operations, and an
  inherited environment is an easy accidental exfiltration path into a user-authored step.
- Surfaced in the UI as "advanced/unsandboxed," opt-in per workspace.

This same argv-array discipline applies to the Git steps (`EnsureCheckout`, `GitFetch`,
`GitFastForward`), since V1 shells out to a real `git.exe` rather than using an embedded library —
see [FE_PIPELINE_STEPS.md](FE_PIPELINE_STEPS.md). Windows argv-quoting has had real historical
bugs for embedded quotes/trailing backslashes; this needs an explicit adversarial-input test
against the pinned JDK's actual `ProcessBuilder`/`CreateProcessW` quoting behavior, not an assumed
guarantee.

## No silent elevation, ever

Any feature that would need admin (enabling WSL2, installing as a Windows Service) is a distinct,
explicit, user-initiated action outside normal app operation — never implied by a "Start" button.

## Update / binary replacement

Both app self-update and managed-runtime binary fetches (the pinned RabbitMQ+Erlang pair) verify a
checksum, and a signature where the upstream publishes one, before replacing anything on disk.
