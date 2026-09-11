# ADR-010: Validate Maven goals against an allow-list, don't attempt bulletproof `cmd.exe` quoting

## Status
Decided.

## Context
`mvn.cmd` is a Windows batch script. Unlike every other spawn in this codebase, `CreateProcessW`
cannot execute it directly — a `.cmd`/`.bat` file can only run via `cmd.exe /c`. WP1's
`WindowsCommandLine` class is adversarially verified (13 cases, round-tripped through the real
`CommandLineToArgvW`) to make `CreateProcessW`'s own argv quoting injection-safe. `cmd.exe`'s `/c`
parsing is a *separate* layer with its own, well-known, not-fully-solvable metacharacter and
variable-expansion quirks — `%` in particular cannot be reliably neutralized in all contexts, a
long-documented limitation other tools and security advisories have run into.

## Decision
`MavenBuildExecutor` validates every goal/phase string against a strict allow-list pattern
(`^[A-Za-z0-9_.:-]+$`) *before* it ever reaches `cmd.exe`. The project directory is never embedded
in the command line at all — it is passed as the spawn's working directory via the OS API
directly, which `cmd.exe` never parses.

## Alternatives rejected
- **Build a second, `cmd.exe`-specific quoting/escaping layer**, mirroring `WindowsCommandLine`'s
  rigor. Rejected for V1: `cmd.exe`'s parsing rules (especially `%` expansion) are genuinely harder
  to make bulletproof than `CreateProcessW`'s own argv rules, and doing this properly would mean an
  entire second adversarial test suite the size of `WindowsCommandLineTest` for a single step type.
  Not ruled out permanently — worth reconsidering if a future step type needs to pass genuinely
  free-form content through a `.cmd`/`.bat` wrapper, where an allow-list isn't an option.
- **Reimplement `mvn.cmd`'s own logic and invoke `java.exe` directly** (Maven's batch script
  ultimately runs `java -cp ... org.codehaus.plexus.classworlds.launcher.Launcher <goals>`,
  bypassing `cmd.exe` entirely). More "pure" from a no-shell-involved standpoint, but fragile: the
  exact classpath/config construction varies across Maven versions and install layouts. Rejected as
  more fragile than it is safer, for no proven safety gain over allow-listing goals.

## Consequences
- Standard Maven goals/phases (`clean`, `install`, `org.apache.maven.plugins:maven-clean-plugin:clean`,
  flags like `-DskipTests`) all pass the allow-list; anything containing a space, quote, or
  shell/cmd metacharacter is rejected outright, with a clear message, before any process spawns.
- This is a real, load-bearing constraint on what `MavenBuild` step params can contain — not just
  documentation. `MavenGoalValidatorTest` covers both real goals and a battery of rejected
  metacharacter/injection-shaped strings.
- If a future need genuinely requires passing a free-form value through a `.cmd`/`.bat` wrapper,
  revisit this ADR rather than silently work around the allow-list.
