# Contributing to Helix-Cloud

Thanks for taking a look at Helix-Cloud. This is pre-1.0, Beta software
maintained by a small team, so please open an issue to discuss non-trivial
changes before investing a lot of time in a pull request.

## Module layout

Helix-Cloud is a Kotlin/Gradle multi-module project:

| Module | What it is |
| --- | --- |
| `helix-api` | Shared contracts/DTOs used across the node, bridges and addons. |
| `helix-node` | The orchestrator core: Task/Service registry, Control API, CLI, addon loader. Produces `Launcher.jar`. |
| `helix-wrapper` | Runs alongside each managed Paper/Velocity process. |
| `helix-bridge-paper`, `helix-bridge-velocity` | Platform-side bridges (backend registration, fallback/lobby routing, maintenance mode). |
| `helix-addon-sdk` | The `AddonBase`/`AddonContext` contract every `.hxa` addon is built against. |
| `helix-addon-*` | Individual addons (bans, chat, economy, friends, guard, …), packaged as `.hxa` files loaded by the node at runtime. |
| `helix-dashboard` | The React/Vite/TypeScript admin panel, bundled into `Launcher.jar` at build time. |

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the full picture and
[docs/ADDONS.md](docs/ADDONS.md) for how to build your own addon.

## Building and testing locally

```bash
./gradlew build                    # compiles every module and runs all tests
./gradlew verifyKDocAvailability    # KDoc gate (see below)
```

`./gradlew check` runs both a module's tests and the KDoc gate for that
module. The `helix-node` build also builds `helix-dashboard` via `pnpm`, so a
working `pnpm` install is required to build that module.

Gradle dependency versions are centralized in
[gradle/libs.versions.toml](gradle/libs.versions.toml) — add new dependencies
there rather than as inline version strings in a module's `build.gradle.kts`.

## The KDoc gate

Every public class, interface, object, function and property in
`src/main/kotlin` must carry a KDoc comment (in English). This is enforced by
`./gradlew verifyKDocAvailability`, which also runs as part of `check` for
every module — a pull request that fails it will fail CI. Comments elsewhere
in the code should explain *why* something is done, not *what* the code
already makes obvious.

## Tests

Add real tests using the existing patterns in the module's `src/test/kotlin`
tree — look at neighboring `*Test.kt` files in the module you're touching
before introducing a new testing style or dependency.

## Pull requests

- Keep changes scoped to one module or feature where possible.
- Match the existing code style: no speculative abstractions or config flags
  for hypothetical futures, no new third-party dependencies unless there is
  genuinely no reasonable way to do the task without one.
- Make sure `./gradlew build` and `./gradlew verifyKDocAvailability` both
  pass before opening the PR — this is exactly what CI checks.
