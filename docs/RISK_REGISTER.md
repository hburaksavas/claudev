# Risk Register

| Risk | Why it matters | Mitigation / status |
|---|---|---|
| RabbitMQ+Erlang portable pair fails on a clean, non-admin VM | M1 release blocker | Spike A: two simultaneous nodes, shared/external EPMD, ASCII + Turkish/non-ASCII paths, safe single-node stop. Not yet run. |
| Job Object crash-safety unproven under an externally-imposed job | Corporate AppLocker/EDR routinely job-wraps processes; this is exactly the scenario that made a workspace-parent job undesirable (ADR-002) | `WindowsJobObjectSmokeTest` proves the binding on a plain dev machine; the external-job case is still a spike, not yet run. |
| SQLite `database is locked` under real-time AV scanning | Windows AV scanning the DB file on write is not fully eliminable | WAL + tuned `busy_timeout`, verified real (not mocked) in `SqlitePragmaConfigurerTest`; exact timeout value should come from a load spike, not a guess. |
| DPAPI round-trip failure on profile reset / roaming-profile edge cases | Would silently lock a user out of stored secrets | Core round-trip verified real in `DpapiSecretStoreTest`; the profile-reset/roaming edge case is not yet tested. |
| Windows argv-quoting/injection in `ExecStep` and the Git steps | Release-blocking security property | Not yet tested against the pinned JDK's actual quoting behavior — see M2 in [MILESTONES.md](MILESTONES.md). |
| Corporate proxy/CA trust incompatibility for `GitFetch`/`MavenBuild` | Silent failure in exactly the locked-down environment this tool targets | `CorporateNetworkEnvironment` forwards proxy/CA env vars by name, but this has only been tested against direct internet access (github.com) — no real corporate proxy/CA available in this environment to verify against. |
| `ExecStep`'s "per-workspace allow-listed executable" check is partial | The full docs/SECURITY.md model calls for a configured allow-list; `ExecStepExecutor` currently only checks the path is absolute and exists | No workspace-settings/allow-list configuration surface exists yet to check against — tracked in `ExecStepExecutor`'s own javadoc, not just here. |
| `MavenBuild`/`ExecStep`/Git steps don't log their constructed argv before spawning | WP8's original acceptance line called for "pre-execution argv is logged and inspectable" — not built yet | Straightforward addition (log the argv `CliProcessRunner` is about to spawn); deferred to keep the WP8 pass focused on the step logic itself, not cut for a hard reason. |
| Licensing/SBOM gap for RabbitMQ (MPL-2.0) + bundled Erlang/OTP | Legal exposure for an OSS tool redistributing binaries | Tracked as an M3 exit criterion, not yet done. |
| `MAX_PATH` / long-path failures in nested workspace/instance directory trees | Silent failures deep in RabbitMQ/Maven's own output paths | `longPathAware` manifest + `\\?\` paths planned; not yet implemented. |
| jlink/jpackage no-admin install failure (JNA native-shim extraction, SQLite JDBC native extraction) | M3 packaging blocker | Not yet spiked — this is why `packaging`'s pom is a placeholder rather than real jpackage config. |
| Unsigned binaries triggering SmartScreen/corporate AV | Adoption friction on the exact fleet this targets | App installer/executable code-signing planned for M3; no code-signing cert acquired yet. |

## Superseded direction (kept for context, not a current risk)

An earlier design round considered React + Electron + a Spring Boot sidecar process. That
introduced a second full process-supervision problem (Electron supervising the sidecar's
lifecycle, a loopback API as a new authenticated network surface, doubled runtime bundle size) for
the one process relationship — UI to its own backend — that the current single-JVM JavaFX design
removes entirely rather than merely governing. See
[adr/ADR-009-javafx-single-jvm-not-electron.md](adr/ADR-009-javafx-single-jvm-not-electron.md).
