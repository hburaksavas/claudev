# Usage

This is a developer-facing guide: how to build, test, run, and extend the codebase as it stands
today. There is no end-user functionality to document yet — `ui-shell` is a placeholder window and
the three runtime adapters are stubs (see [REPO_LAYOUT.md](REPO_LAYOUT.md), "What 'stub' means
here"). [MILESTONES.md](MILESTONES.md) tracks what turns this into a working application.

## Prerequisites

- **JDK 21**, specifically. The build targets `--release 21` (`pom.xml`'s
  `maven.compiler.release`); a JDK 17 (or any pre-21) `JAVA_HOME`/`PATH` entry will fail the build
  with a release-version error, not a warning. If multiple JDKs are installed, point `JAVA_HOME`
  explicitly:

  ```bash
  # bash (Git Bash / WSL / macOS / Linux)
  export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.x.x.x-hotspot"   # adjust to your install
  ```

  ```powershell
  # PowerShell
  $env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.x.x.x-hotspot"
  ```

- **Maven 3.9+** on `PATH` (no wrapper is committed yet — `mvnw` could be added later if the team
  wants one).
- **Windows**, for anything beyond `domain-core`/`provider-api`. `platform-windows` (JNA Win32
  bindings) and `secret-store-dpapi` (DPAPI) call real OS APIs and only make sense on Windows; see
  "Platform-specific tests" below for how they behave elsewhere.

## Build

From the repo root (`claudev/`, where the parent `pom.xml` lives):

```bash
mvn compile
```

Builds all 13 modules in dependency order. A clean checkout's first build will download Spring
Boot's dependency BOM, JavaFX (`win` classifier), JNA, Lettuce, sqlite-jdbc, JUnit 5, Mockito, and
AssertJ from Maven Central — expect that first run to take longer.

## Test

```bash
mvn test
```

Runs every module's test suite. Notably, several tests are **not mocked** — they call real
Windows APIs on the machine running the build:

| Test | What it actually does |
|---|---|
| `platform-windows` → `WindowsJobObjectSmokeTest` | Spawns a real child process (`ping`), assigns it to a real Win32 Job Object with `KILL_ON_JOB_CLOSE`, terminates the job, asserts the child process actually died. |
| `secret-store-dpapi` → `DpapiSecretStoreTest` | Round-trips a real string through Windows DPAPI (`CryptProtectData`/`CryptUnprotectData`). |
| `persistence` → `SqlitePragmaConfigurerTest` | Opens a real temp-file SQLite database and asserts WAL mode and `busy_timeout` actually took effect. |

These guard with `Assumptions.assumeTrue(...)` on `os.name` containing "win", so `mvn test` on a
non-Windows machine skips them cleanly instead of failing — but that means those specific
guarantees are **not checked at all** off Windows. A Windows CI leg is not optional; see
[TESTING_STRATEGY.md](TESTING_STRATEGY.md).

### Running a single module's tests

```bash
mvn -pl platform-windows test
```

(`-pl` still parses the whole reactor's `pom.xml` tree, so every module needs a valid `pom.xml`
even if you're only building one — this is normal Maven multi-module behavior, not something
specific to this repo.)

## Run the application

`spring-boot:run` invoked as a bare goal runs across every project in the reactor it's given, not
just the one you name with `-pl` — combined with `-am` that includes the parent aggregator (which
has no `mainClass`) and fails before ever reaching `app-bootstrap`. Install the modules to the
local repo once, then run `app-bootstrap` on its own:

```bash
mvn install -DskipTests          # once per change to a non-app-bootstrap module
cd app-bootstrap
mvn spring-boot:run
```

This starts the Spring context (`ClaudevApplication.main`) — including the `persistence` module's
`PersistenceConfig`, which opens a real SQLite file under `~/.claudev/claudev.db` and applies the
WAL/busy_timeout pragmas — then launches the JavaFX window (`ClaudevShell`) against it. Today that
window shows only a placeholder label, since `ui-shell` has no real views yet.

Equivalent two-step alternative (build a runnable jar, then run it):

```bash
mvn -pl app-bootstrap package   # after the install step above
java -jar app-bootstrap/target/claudev.jar
```

**Verified**: this exact two-step (`install` then `spring-boot:run` from `app-bootstrap/`) has been
run for real — `Started ClaudevApplication` logs, the JavaFX window opens (title "claudev"), and
the process stays alive with no further errors. A harmless
`Unsupported JavaFX configuration: classes were loaded from 'unnamed module'` warning is expected
(the app runs JavaFX in classpath mode, not module-path mode) and is not a failure.

A `spring.datasource.url`-based single-goal invocation (`mvn -pl app-bootstrap -am spring-boot:run`)
will fail with "Unable to find a suitable main class" — this is a Maven reactor quirk with bare-goal
CLI invocation across `-am`-pulled modules, not a project misconfiguration; use the two commands
above instead.

To quit, closing the window only hides it (`Platform.setImplicitExit(false)`, per
[PROCESS_SAFETY.md](PROCESS_SAFETY.md)'s "window close = tray/minimize" decision) — there is no
tray icon or Quit menu item wired up yet, so today the only way to actually stop the app during
development is `Ctrl+C` in the terminal running `spring-boot:run` (or killing the `java` process).
Wiring a real Quit action to `ClaudevShell.quit()` is M0/M3 backlog.

## Package (installer)

Not available yet. `packaging/pom.xml` is a deliberate placeholder — see
[RISK_REGISTER.md](RISK_REGISTER.md) ("jlink/jpackage no-admin install failure") and M3 in
[MILESTONES.md](MILESTONES.md). Do not add real `jpackage` plugin config there until that spike has
run; a plausible-looking but untested packaging config would be worse than an honest placeholder.

## Finding your way around

- [REPO_LAYOUT.md](REPO_LAYOUT.md) — what each of the 13 modules is for and the dependency graph.
- [ARCHITECTURE.md](ARCHITECTURE.md) — the layering and why the DTO boundary discipline exists.
- [MILESTONES.md](MILESTONES.md) — the backlog, in order, with acceptance gates.
- [adr/](adr/) — why each hard-to-reverse decision was made, and what was rejected.

## Extending a stub adapter (e.g. `adapter-redis`)

1. Read the relevant port interface in `provider-api` (`ConnectionProvider` for Redis) and its
   design doc ([REDIS_SCOPE.md](REDIS_SCOPE.md)).
2. Replace the `notImplemented()` bodies in `RedisConnectionProvider` with a real implementation —
   for Redis, that means wiring the already-declared `lettuce-core` dependency.
3. Do not widen the port interface's contract casually — `provider-api` is meant to be a stable
   seam (see [PLUGIN_CONTRACT.md](PLUGIN_CONTRACT.md)); if the interface itself needs to change,
   that's worth a moment's thought about whether it's still describing the same DTO-boundary
   discipline (owned, serializable types; no raw handles; `ProviderResult`/`ProviderError`, not
   thrown exceptions).
4. Add real tests. If the change touches Windows-specific behavior (a new spawn path through
   `platform-windows`, for instance), prefer a real, unmocked test over a mocked one where
   feasible — see the existing `WindowsJobObjectSmokeTest`/`DpapiSecretStoreTest` for the pattern.
5. Update the relevant module's `pom.xml` `<description>` and this repo's docs if the module's
   scope or milestone status changed.

## Common issues

- **`release version 21 not supported`** — `JAVA_HOME`/`PATH` is pointing at a pre-21 JDK. See
  Prerequisites above.
- **JavaFX dependency fails to resolve** — `ui-shell/pom.xml` pins the `win` classifier for
  `javafx-controls`/`javafx-fxml` (this product targets Windows only). Building on a non-Windows
  machine will need that classifier changed to `linux`/`mac` locally; don't commit that change
  without discussion, since it would silently misrepresent the product's actual target platform.
- **Garbled characters in build output on a Turkish-locale Windows shell** — the build itself is
  UTF-8 (`project.build.sourceEncoding`); this is a terminal code-page display issue, not a build
  correctness issue.
