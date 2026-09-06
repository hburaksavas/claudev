# FE Pipeline Step Catalog

Declarative, versioned, typed steps interpreted by a fixed step-type registry — never compiled
per-project adapter code, which would reopen the dynamic-trust problem V1 already closed for
plugins in general (see
[adr/ADR-001-modular-monolith-not-dynamic-plugins.md](adr/ADR-001-modular-monolith-not-dynamic-plugins.md)).
Each step's parameters are validated against a schema owned by the step type in Java — never
interpreted as a free-form shell string.

## Steps

| Step | Purpose | Notes |
|---|---|---|
| `EnsureCheckout` | Clone if absent, otherwise no-op | |
| `GitFetch` | Fetch from a named remote | Separate from fast-forward on purpose |
| `GitFastForward` | Fast-forward the working branch | A dirty or non-fast-forward worktree **blocks**, never auto-merges |
| `MavenBuild` | Run declared goals | |
| `ConfigPatch` | Key→value substitutions (or template + values) against declared files, validated against a schema | Never a free-form find/replace across the whole repo tree |
| `StageArtifact` | Copy a built artifact to a staging path | |
| `AtomicDeploy` | Move staged output into the live deploy path atomically | |
| `StartProcess` | Launch the deployed service | |
| `HealthCheck` | Poll a URL until ready or timeout | |
| `ExecStep` | The one escape hatch | See [SECURITY.md](SECURITY.md) — allowlisted executable, argv array, bounded cwd/env, timeout, per-workspace opt-in, "unsandboxed" UI label |

`GitFetch`/`GitFastForward` replace an earlier, ambiguous single "GitPull" step: pulling silently
merges or fails in ways that are hard to reason about in an unattended pipeline; splitting the two
makes "the worktree wasn't fast-forwardable" a first-class, visible failure instead of a merge
commit nobody asked for.

## Git integration: system/imported `git.exe`, not an embedded library

V1 shells out to a validated, absolute path to `git.exe` (system or user-imported) rather than
bundling Git or using an embedded library (e.g. JGit/libgit2 bindings). Reasoning: corporate
proxy/CA/SSH configuration is usually already correctly set up for the user's existing `git.exe`
install; reproducing that trust/config surface inside an embedded library is extra work with no
clear V1 payoff. This is re-evaluated only if corporate proxy/CA/SSH compatibility with the shelled
approach proves not to work in practice (see [RISK_REGISTER.md](RISK_REGISTER.md)).

**Because it's shelled out, the Git steps get exactly the same argv-array/no-shell-string
discipline as `ExecStep`** — "it's a built-in step" is not an exemption from that rule. The
absolute path to `git.exe` is validated once (centralized `validatePath`), and every argument is a
distinct array element, never concatenated into a command-line string.

## Corporate network reality

`GitFetch` and `MavenBuild` must honor the system proxy configuration and corporate CA trust
store, or the pipeline fails silently in exactly the locked-down corporate environment this tool
targets. This is a stated non-functional requirement on those step adapters, not an assumed
default.
