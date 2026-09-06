# RabbitMQ Runtime

## Sourcing

One pinned, checksum-verified RabbitMQ+Erlang/OTP version pair per platform build — not "bundle
whatever RabbitMQ release is current." `RuntimeSource.Managed(version, erlangVersion,
checksumSha256)` in `domain-core` models exactly this. `Imported`/`System` sources remain available
for users who already manage their own install.

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

## Release-blocking feasibility spike (not yet run)

Before M2 can be attempted:

- Two simultaneous managed nodes on one machine, distinct nodename/port/cookie/data-dir.
- Safe single-node stop that doesn't disturb the other node.
- Behavior when an external EPMD is already present on the machine.
- Non-ASCII (e.g. Turkish) Windows user-profile paths.

See [RISK_REGISTER.md](RISK_REGISTER.md) — "portable Rabbit+OTP clean-VM spike failure" is an
explicit release blocker, not a nice-to-have.

## Licensing

RabbitMQ is MPL-2.0; the bundled Erlang/OTP distribution has its own license. Both need an
explicit license/provenance review before this ships as an open-source tool redistributing them —
tracked as a milestone-1 exit criterion (see [MILESTONES.md](MILESTONES.md)), not something to
discover at release time.
