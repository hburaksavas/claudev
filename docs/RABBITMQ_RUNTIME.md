# RabbitMQ Runtime

## Status: adapter built, verified, and wired into the running app

`adapter-rabbitmq`'s `RabbitMqRuntimeProvider` is real and dispatched from the workspace UI (WP6);
see docs/MILESTONES.md WP6 for what's built and the real bugs found along the way (a missing `PATH`
broke every spawn, and a real `await_startup` race). `RabbitMqProviderHolder` provisions the pinned
pair lazily on first use, not at app startup.

## Sourcing

One pinned, checksum-verified RabbitMQ+Erlang/OTP version pair per platform build — not "bundle
whatever RabbitMQ release is current." `RuntimeSource.Managed(version, erlangVersion,
checksumSha256)` in `domain-core` models exactly this.

`Imported` (a user's own Erlang/RabbitMQ binaries) is real too (WP10b):
`RabbitMqRuntimeProvider.fromImported(erlangHome, rabbitmqSbin)` validates both paths and skips
provisioning entirely; set `claudev.rabbitmq.imported-erlang-home` and
`claudev.rabbitmq.imported-rabbitmq-sbin` to use it as a config-property fallback.
`System` (auto-discovered, never lifecycle-owned) is not built.

### Auto-detection (WP10d)

The "New RabbitMQ instance" dialog no longer asks the user to type paths first: it scans the
machine on open via `adapter-rabbitmq.detect.RabbitMqInstallDetector`, which combines
`RabbitMqDetector` (registry `Uninstall` keys, a `rabbitmq_server-*` glob under
`Program Files\RabbitMQ Server`, and `PATH`) and `ErlangDetector` (`ERLANG_HOME`, registry, and an
`erl-*` glob under `Program Files`) — the underlying OS-scanning primitives
(`RegistryUninstallScanner`, `PathEnvironmentScanner`, `GlobDirectoryScanner`,
`EnvironmentVariableReader`) live in `platform-windows.detect` and know nothing about
RabbitMQ/Erlang specifically.

Every RabbitMQ×Erlang pairing found is checked against `RabbitMqErlangCompatibility` — a small
hardcoded allow-list (RabbitMQ major 4 with Erlang/OTP major 26–27), with the proven-broken
RabbitMQ 4.3.5 + OTP 29 pair explicitly excluded regardless of the window. A RabbitMQ install with
no compatible Erlang install is dropped entirely; only compatible pairs are ever offered to the
user (as `domain-core`'s `DetectedCandidate`). Zero candidates falls back to the manual
Erlang-home/RabbitMQ-sbin text fields (with a `Browse...` directory chooser), same as before.

**Known limitation**: `RabbitMqProviderHolder` is a single process-wide provider (one active
Erlang/RabbitMQ pair per app run — see its Javadoc). Picking a second, different candidate once a
provider is already active/configured throws `IllegalStateException` rather than silently
switching; restart the app to choose a different install. This is a real constraint of the current
single-provider design, not a bug.

## Plugin management (WP10c)

`RabbitMqRuntimeProvider.listEnabledPlugins`/`enablePlugin`/`disablePlugin` are real — a RabbitMQ-
specific escape hatch (not a `RuntimeProvider` port method; see docs/MILESTONES.md WP10c) that
spawns `rabbitmq-plugins.bat` the same way `rabbitmqctl.bat` is spawned. A real quirk: `enable` on a
nonexistent plugin name exits `0` (a WARNING, not an error), so success is always verified against a
fresh `list -e -m` read, never the exit code.

## Per-instance isolation

- Foreground node (not installed as a Windows Service — that needs admin).
- Per-instance Job Object and data/log directories (see [PROCESS_SAFETY.md](PROCESS_SAFETY.md)).
- Loopback-only listeners.
- Unique `RABBITMQ_NODENAME`, unique ports, unique cookie environment **per instance** — the
  default `.erlang.cookie` location is per-user under `%USERPROFILE%`, which is *shared* across
  instances unless overridden via `RABBITMQ_CONFIG_FILE`/env. Since the app can't touch machine
  PATH/registry without admin, every spawn passes a fully explicit environment block
  (`ERLANG_HOME`, `RABBITMQ_BASE`, `RABBITMQ_NODENAME`, `RABBITMQ_CONFIG_FILE`, cookie path) rather
  than relying on anything inherited from the user's shell.

## EPMD is not owned by any single instance

EPMD (Erlang Port Mapper Daemon) is a separate, application-level managed dependency with a
stable, non-default loopback port and a **ref-counted** lifetime across however many RabbitMQ
instances are running. It must never be born inside, or killed with, a single instance's Job
Object — stopping one RabbitMQ node must never disturb another node or a pre-existing external
EPMD.

## Release-blocking feasibility spike — RUN, PASSED (2026-09-12)

All four criteria verified against real binaries on this dev machine (not simulated, not mocked):
portable `otp_win64_27.3.4.17.zip` (Erlang/OTP 27.3.4.17 — **not** the newest OTP-29.0.6, which
failed to boot RabbitMQ 4.3.5 at all, see below) and `rabbitmq-server-windows-4.3.5.zip`, both
official GitHub-release zip distributions requiring no installer/no admin.

- **Two simultaneous managed nodes, distinct nodename/port/cookie/data-dir**: `spikeA@localhost`
  (port 5673, dist port 25673, cookie `spike_cookie_A`) and `spikeB@localhost` (port 5674, dist
  port 25674, cookie `spike_cookie_B`) both booted and ran concurrently (`rabbitmqctl status`
  confirmed both). Every value came from an explicit environment block passed to
  `rabbitmq-server.bat` (`ERLANG_HOME`, `RABBITMQ_BASE`, `RABBITMQ_NODENAME`, `RABBITMQ_NODE_PORT`,
  `RABBITMQ_DIST_PORT`, `RABBITMQ_SERVER_START_ARGS=-setcookie ...`) — nothing inherited from a
  shared `.erlang.cookie` or ambient shell state, matching the per-instance-isolation section above.
- **Safe single-node stop**: `rabbitmqctl -n spikeA@localhost stop` (cookie passed via
  `RABBITMQ_CTL_ERL_ARGS`) cleanly exited node A's OS process while node B's uptime kept
  incrementing without interruption and EPMD's registry (`epmd -names`) dropped only `spikeA`.
- **External/pre-existing EPMD**: after both A and B were stopped, `epmd.exe` (a separate,
  unmanaged process — confirmed by PID) kept running on its own, exactly as
  ["EPMD is not owned by any single instance"](#epmd-is-not-owned-by-any-single-instance) above
  describes. A third node (`spikeC@localhost`) was then started against that already-running EPMD
  and booted normally without EPMD being respawned (same PID before and after) — confirms the
  "app starts into a machine that already has EPMD running" case is unremarkable, not a special
  case to code around.
- **Non-ASCII Windows path**: node A's entire `RABBITMQ_BASE` lived under a Turkish-character
  directory (`...\örnek-çalışma-alanı\node-A`) — mnesia/feature-flags/log directories were all
  created and written under it correctly, and the node booted, ran, and stopped cleanly. (The
  *console* mirrored the path with mangled characters — a `chcp`/codepage display artifact in the
  terminal used to launch it, not a file I/O problem; the actual on-disk paths and log file
  contents were correct.)

**A real, release-relevant finding, not a process note:** the newest available Erlang/OTP at spike
time (OTP-29.0.6) **fails to boot RabbitMQ 4.3.5 outright** —
`{incompatible_feature_flags,{horus,extraction_denied,...}}` during `Starting broker...`, i.e.
RabbitMQ's `horus` abstract-code loader rejects OTP 29's newer bytecode. This is exactly why
`RuntimeSource.Managed` pins an explicit `(version, erlangVersion)` **pair** rather than "whatever
Erlang is newest" — confirmed necessary, not a defensive design that turned out unneeded. OTP
27.3.4.17 is the version this spike verified as compatible with RabbitMQ 4.3.5; the actual pin
(with a checksum from an authoritative source) is still an M1 exit criterion, not decided here —
see the Licensing/checksum note below.

**Spike binaries** (not committed — ~640MB) are on this machine at
`D:\dev\workspace\claudev-spike` (`otp27\` and `rabbitmq\rabbitmq_server-4.3.5\`) for reuse by
whoever picks up WP6's actual `adapter-rabbitmq` build, so it isn't re-downloaded from scratch.

See [RISK_REGISTER.md](RISK_REGISTER.md) — the "portable Rabbit+OTP clean-VM spike failure" risk is
now resolved for *this* machine; a true clean-VM (no dev tools, no existing Erlang/PATH state) run
remains worth doing before shipping, since this spike ran on an already-JDK/Maven/git-equipped dev
box, not a minimal user machine.

## Licensing

RabbitMQ is MPL-2.0; the bundled Erlang/OTP distribution has its own license. Both need an
explicit license/provenance review before this ships as an open-source tool redistributing them —
tracked as a milestone-1 exit criterion (see [MILESTONES.md](MILESTONES.md)), not something to
discover at release time.
