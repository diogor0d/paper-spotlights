# Contributing to PaperSpotlights

PaperSpotlights is intentionally a small, predictable Paper plugin. Contributions should preserve its private-SMP gameplay, world safety, and low coupling to Minecraft internals.

[AGENTS.md](AGENTS.md) contains the authoritative architecture, compatibility contracts, and safety invariants. Those constraints apply to human and automated contributors.

## Before starting

- Search existing issues before reporting a bug or proposing a feature.
- Small fixes, focused tests, and documentation improvements may be submitted directly.
- Open an issue before substantial gameplay changes, new dependencies, persistence-schema changes, updater changes, or Paper-version migrations.
- Do not add permissions, runtime reload support, NMS, CraftBukkit internals, reflection, packets, or version-specific dispatch without an accepted design change.
- Report vulnerabilities privately through [SECURITY.md](SECURITY.md). Do not publish exploit details, tokens, private server data, or sensitive logs in an issue.

## Development setup

Requirements:

- Git
- JDK 25
- No system Maven installation; use the included checksum-pinned Maven Wrapper

Windows:

```powershell
.\mvnw.cmd clean verify
```

Linux/macOS:

```bash
bash ./mvnw clean verify
```

The verified artifact is `target/PaperSpotlights.jar`.

For a faster feedback loop, `test` is acceptable during development. Run `clean verify` before submitting because it also packages and validates the real plugin JAR.

## Coding expectations

- Use Java 25 and public Paper/Bukkit APIs.
- Keep Bukkit world, block, entity, scheduler, and persistent-data access on the server thread.
- Keep network and download work outside the server thread.
- Preserve `LightFieldService` as the sole writer of managed `LIGHT`, air, and water states.
- Persist new claims before changing world blocks.
- Do not force-load chunks or perform unbounded block updates in event handlers.
- Preserve maximum-intensity overlap behaviour, waterlogging, foreign-block safety, and failure-closed state loading.
- Preserve existing persistent-data keys and stored-state compatibility unless the change includes an explicit migration.
- Prefer immutable model values and small deterministic units that can be tested without a running server.
- Avoid new dependencies when Java 25 or Paper already provides the required functionality.
- Follow `.editorconfig`: four-space Java indentation, two-space YAML/XML indentation, UTF-8, LF, a final newline, and no trailing whitespace.
- Compilation warnings are errors; new code must compile cleanly.
- Update tests, README instructions, configuration comments, and security documentation when behaviour changes.

Do not commit generated artifacts, IDE state, server directories, downloaded Paper or Minecraft JARs, worlds, plugin runtime state, logs, secrets, or private server information.

## Testing

Add focused tests for every changed deterministic behaviour. Existing coverage includes:

- Spotlight geometry and immutable model validation.
- Controller dial mapping.
- Contribution overlap and event filtering.
- Persistence round trips, backups, corrupt data, and schema handling.
- Semantic versions and release parsing.
- Updater redirects, limits, digests, compatibility, interruption, and cleanup.
- Filtered `plugin.yml` and validation of the packaged JAR.

Unit tests do not emulate Paper's lighting engine or event ordering. Changes affecting gameplay, persistence, entities, chunks, fluids, or world restoration also require manual testing on a disposable or backed-up server with the matching Paper and Java versions. Accept Minecraft's EULA only in an environment you control and are authorized to operate.

Relevant manual checks include:

1. Clean enable, disable, and restart restoration.
2. Circle and square spotlights on horizontal and vertical surfaces.
3. Controller cycling, exact levels, toggling, overlaps, and both removal paths.
4. Block placement/removal, water, pistons, or explosions when affected.
5. Chunk unload/reload and item-frame entity reload.
6. Update staging and application on the next restart when updater code changes.

Do not use `/reload` or plugin hot-reload tools for testing.

## Bug reports

Include enough information to reproduce the problem:

- Paper version and build.
- Java version.
- PaperSpotlights version or commit.
- Relevant configuration with private repository names or server details redacted.
- Minimal reproduction steps, expected result, and actual result.
- The smallest useful log excerpt or stack trace.

State whether another plugin or command modified the affected blocks or controller entities. Never upload a world, `state.yml`, tokens, IP addresses, or full server logs unless you have reviewed and sanitized them.

## Pull requests

Keep each pull request focused. Include:

- The problem and why the change fits the project.
- A concise implementation summary.
- Automated test results.
- Manual Paper test results, or an explanation when they are not applicable.
- User/operator documentation changes.
- Compatibility, persistence, performance, and security implications.

Before submission:

```powershell
.\mvnw.cmd clean verify
git diff --check
```

On Linux/macOS, use `bash ./mvnw clean verify` for the first command.

Do not bump the project version, create release tags, or alter release assets unless the maintainer requested release work.

## Releases

Releases are maintainer-controlled:

1. Set a stable semantic version in `pom.xml`.
2. Run `clean verify`.
3. Complete the backed-up Paper staging checklist.
4. Create a matching `vMAJOR.MINOR.PATCH` tag.
5. Allow the release workflow to publish exactly `PaperSpotlights.jar`.

The first build after raising `api-version` must be installed and tested manually. The updater intentionally refuses to cross API channels.

## Third-party material and licensing

Submit only code, documentation, and assets that you created or have the right to contribute. Do not copy material with unknown or incompatible terms.

This repository does not yet have a `LICENSE`. Until the owner selects one, reuse and external-contribution terms are unresolved. Issues and design discussions are welcome, but substantial external code should not be merged until those terms are made explicit. Contributors and agents must not invent a license on the owner's behalf.
