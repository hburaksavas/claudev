# Testing Strategy

## Layers

- **Domain unit tests** (`domain-core`): pure, no framework — invariants on records (e.g.
  `OwnerSid` rejects blank, `RedisSafetyPolicy.restrictiveDefault()` is actually restrictive).
- **Provider contract tests** (`provider-api`): assert `ProviderResult`/`ProviderError` shape and
  behavior (`map`, `isOk`) stays stable — this is what keeps a future out-of-process protocol
  extraction mechanical.
- **Real native-call tests, not mocked**, where the whole point is a live OS interaction:
  - `platform-windows`: `WindowsJobObjectSmokeTest` spawns a real child process, assigns it to a
    real `KILL_ON_JOB_CLOSE` Job Object, terminates the job, and asserts the child actually died.
  - `secret-store-dpapi`: `DpapiSecretStoreTest` round-trips a real value through
    `CryptProtectData`/`CryptUnprotectData`.
  - `persistence`: `SqlitePragmaConfigurerTest` opens a real temp SQLite file and asserts WAL mode
    and `busy_timeout` actually took effect.

  These are guarded with `Assumptions.assumeTrue(... "win" ...)` so they skip cleanly on a
  non-Windows CI runner rather than failing — but a **Windows CI leg is mandatory**, not optional;
  skipping everywhere would mean these guarantees are never actually checked.

- **Spring wiring tests** (`app-bootstrap`): `@SpringBootTest` without calling `main()`, so the
  test never launches JavaFX. `DataSourceAutoConfiguration` is excluded until `persistence` has
  real `DataSource` wiring — see [PERSISTENCE.md](PERSISTENCE.md).

## Not yet in place (M0+ backlog)

- TestFX (or equivalent) for `ui-shell` view/ViewModel tests, headless where possible.
- The crash-and-relaunch reconciliation drill: externally hard-kill the JVM with several dummy
  instances running, relaunch, assert the reconciler classifies every process correctly (no false
  `RUNNING`, no false `ORPHANED` for genuinely-dead entries, any unrecorded-but-running process
  surfaces as `UNTRACKED`).
- Adversarial argv-quoting tests for `ExecStep` and the Git steps (embedded quotes, trailing
  backslashes, `&|<>^`, empty strings) against the pinned JDK's actual `ProcessBuilder`/
  `CreateProcessW` behavior — this quoting logic has had real historical JDK bugs and must not be
  assumed safe by analogy to any other language's guarantees.
- A `jpackage`-built-artifact smoke install/run on a clean, non-admin Windows VM (both ASCII and
  Turkish/non-ASCII profile paths).

## Principle

A stub adapter returning `ProviderResult.err(NOT_IMPLEMENTED)` is itself a passing, meaningful
test target: callers should be tested against the *contract* (does the caller handle an `Err`
correctly?) before a real adapter exists, not only after.
