# PaperSpotlights agent guide

## Scope and purpose

This file applies to the entire repository. It is the durable implementation context for coding agents and maintainers. Keep it aligned with code, tests, `README.md`, `CONTRIBUTING.md`, and the GitHub workflows whenever a project-wide decision changes.

PaperSpotlights is a small Paper plugin for a private SMP. Players define a fixture origin, a target surface, and a clock-in-item-frame controller; the plugin creates dimmable circle or square footprints from vanilla invisible `LIGHT` blocks. Simplicity, predictable world behaviour, and low version-coupling take priority over feature breadth.

## Current baseline

- Plugin version: read from `pom.xml`; Maven filters it into `plugin.yml`.
- Target: Paper 26.2, currently pinned to `paper-api 26.2.build.62-beta`.
- Runtime and compiler: Java 25.
- Build: Maven Wrapper 3.9.11 with a pinned distribution SHA-256.
- Descriptor: standard `plugin.yml`; do not migrate to experimental `paper-plugin.yml` without an explicit project decision.
- Persistent schema: version 2 in `plugins/PaperSpotlights/state.yml`; schema 1 migrates to manual, uncolored defaults before world writes.
- External runtime dependencies: none beyond Paper.

The pinned Paper build is a beta. A clean compile is necessary but not sufficient for release; world-writing changes must also be exercised on a backed-up Paper staging server.

## Repository map

| Area | Responsibility |
|---|---|
| `PaperSpotlightsPlugin` | Lifecycle, config validation, registration, asynchronous updater startup. |
| `SpotlightCommand` / `SpotlightListener` | Player commands, lens selection, controller events, and world-event reconciliation. |
| `SpotlightManager` | Authoritative in-memory definitions, indexes, mutations, persistence ordering, and bounded work queue. |
| `light/LightFieldService` | Contribution overlap, claim ownership, and all physical `LIGHT`/water world writes. |
| `color/ColoredLightEffectService` | Bounded, player-targeted cosmetic color washes; never writes world state. |
| `persistence/SpotlightRepository` | Versioned YAML state, backup, and replace-on-save behaviour. |
| `model/` | Immutable coordinates, planes, shapes, spotlight records, and pure geometry. |
| `controller/` / `setup/` / `ui/` | Clock dial mapping, per-player setup sessions, lens metadata, messages, and previews. |
| `update/` | Bukkit-independent GitHub Releases client, parsing, validation, download, and atomic staging. |
| `src/test` | Pure unit tests plus `PackagedArtifactIT`, which validates the actual built JAR during `verify`. |

## Non-negotiable design invariants

### Version resilience

- Use only public Paper/Bukkit APIs. Do not add NMS, CraftBukkit internals, reflection, packets, or version-string dispatch unless the user explicitly changes the project direction.
- Keep version-sensitive values isolated in `pom.xml` and `src/main/resources/plugin.yml`.
- Prefer world `NamespacedKey` values over names or implementation handles.
- Keep dependencies minimal. Do not add a library for functionality that Java 25 or Paper already provides cleanly.

### World and thread safety

- Bukkit world, block, entity, scheduler, and persistent-data operations stay on the server thread. The updater may do network and file work asynchronously because it has no Bukkit world interaction.
- `LightFieldService` is the only component that writes vanilla `LIGHT` blocks or restores water/air at managed coordinates.
- Only loaded air cells may become newly managed. Never replace builds, plants, fluids, or foreign/unowned `LIGHT` blocks.
- A real block wins over light. If a relevant cell later becomes air, bounded reconciliation may restore its active light.
- Preserve `Waterlogged` when changing a light level. Releasing or explicitly cleaning a waterlogged light restores `WATER`, never air.
- Overlap resolves to the maximum active intensity. Removing or disabling one owner must not erase another owner's required light.
- Do not force-load chunks. Chunk-load events and the loaded-position sweep reconcile dormant cells.
- Filter event coordinates through the contribution/managed index before enqueueing them. Unrelated block or fluid activity must not consume the bounded queue.
- Keep physical changes bounded by `changes-per-tick`; do not move unbounded block work back into player event handlers.
- Night-only output is derived from each loaded world's time and the persisted master switch. Dusk claims must be persisted before lighting; dawn cleanup remains bounded.
- Colored effects stay cosmetic and bounded. They must not force-load chunks, create persistent entities, or imply that vanilla lighting has RGB support.

### Persistence ordering

- `state.yml` is authoritative because vanilla light blocks cannot store per-plugin ownership.
- A new baseline claim must be persisted before any corresponding `LIGHT` is written. If persistence fails, roll back the new in-memory claim and leave the world unchanged.
- Definition mutations must persist successfully before their world reconciliation is enqueued. Failed mutations restore the prior definitions, indexes, contributions, and managed claims.
- Corrupt, unsafe, duplicate, or unsupported state fails closed: log the reason and disable the plugin without writing world state.
- `state.yml.bak` is manual recovery material. Never silently load it; it may be stale.
- Preserve the schema version check. A schema change requires migration logic, rollback analysis, tests, and documentation.

### Player experience

- There are intentionally no permissions. This is a private-SMP design choice, not an omission.
- Preserve the Gaffer's Lens flow: left-click origin, right-click target surface, right-click a clock item frame, then `/sl create`.
- The clock has eight levels (`1, 3, 5, 7, 9, 11, 13, 15`); normal click advances and sneak-click toggles without forgetting intensity.
- Right-clicking a controller with a vanilla dye applies its non-consuming color wash. `/sl color <name> none` disables it.
- Night-only automation is per spotlight. The persisted `enabled` flag remains its master switch; the schedule gates effective output without overwriting that choice.
- Breaking the controller removes its spotlight. `/sl remove` keeps the frame.
- Holding the lens reveals native light blocks. Sneak-left-click cleanup is explicitly destructive and may remove a light owned by another plugin; keep the warning visible in player/operator documentation.
- Do not introduce reload support. Restart-based configuration changes are deliberate for persistent world state.

### Compatibility contracts

- Preserve the persistent-data keys `gaffers-lens` and `spotlight-id`. Renaming either requires an explicit migration plan for existing items and entities.
- Spotlight names are unique case-insensitively. Each controller belongs to at most one spotlight, and the origin, target, and controller must share a world.
- Persisted intensity is `1-15`; OFF is represented by `enabled: false` so the selected level is retained.
- Persisted `night-only` and `color` were introduced in schema 2. Schema 1 maps them to `false` and `none`; malformed schema 2 fields fail closed.
- Automatic night is the normalized world-time interval `[13000, 23000)`. Weather and local block light do not affect it.
- `none` plus all 16 vanilla dye IDs are stable persisted color values. Color particles never replace native white illumination.
- Keep `config.yml`, README examples, runtime validation, and tests synchronized. Current accepted ranges are `max-radius` 1-32, `changes-per-tick` 16-2048, and updater size 1-64 MiB.
- Do not regenerate the Maven Wrapper without pinning both the Maven version and the distribution SHA-256.
- Never edit generated files under `target/`; change their source inputs instead.

### Automatic updater

- A blank `updates.repository` means no network request. Keep that safe default until the real public `OWNER/REPOSITORY` is known.
- The updater accepts only the configured public GitHub repository, the latest non-draft/non-prerelease release, strict SemVer tags, and exactly one configured asset name.
- Require GitHub's `sha256:` asset digest, stream with a hard size limit, validate redirects and HTTPS hosts, and stage through a same-directory temporary file plus atomic move.
- Validate the downloaded JAR's `plugin.yml` identity, release version, exact API channel, declared main-class entry, and class-file magic. Reject a conflicting `paper-plugin.yml`.
- Never overwrite or hot-reload the running JAR. Stage only in Paper's configured update directory for the next restart.
- Never auto-cross `api-version`. The first build on a newer API channel is a manual, staging-tested installation.
- Digest verification protects integrity, not a compromised GitHub maintainer account. Keep this limitation explicit in `README.md` and `SECURITY.md`.

## Build and verification

Windows:

```powershell
.\mvnw.cmd clean verify
```

Linux/macOS:

```bash
bash ./mvnw clean verify
```

`verify` must remain the release gate. It runs warning-as-error Java compilation, unit tests, packages `target/PaperSpotlights.jar`, and runs `PackagedArtifactIT` against that JAR. Do not publish from `mvn test` alone.

Add focused deterministic tests for pure geometry, dial behaviour, parsing, persistence, updater failures, and invariants. Tests involving real lighting, chunk lifecycle, water flow, entity lifecycle, or Paper event ordering still need a real Paper staging smoke test.

Before a release tag, manually test on a backed-up matching Paper server:

1. Enable and restart/state restoration.
2. Create circle and square spotlights on floors and walls.
3. Dial, exact-level, dye color/none, night schedule, toggle, overlap, and removal behaviour.
4. Block placement/removal, waterlogging, explosions/pistons where relevant.
5. Chunk unload/reload and controller entity reload.
6. Update staging and next-restart replacement.

## Version and release procedure

For a normal plugin release:

1. Change the single project version in `pom.xml`.
2. Run `clean verify`; resource filtering and packaged-artifact tests must confirm the descriptor version.
3. Complete the real-Paper smoke checklist.
4. Create a stable `vMAJOR.MINOR.PATCH` tag matching the POM.
5. Let `.github/workflows/release.yml` publish exactly `PaperSpotlights.jar`.

For a Paper/Minecraft target update:

1. Pin a concrete `paper.version` in `pom.xml`.
2. Keep `api-version` unchanged when the plugin still uses that older public API; raise it only when newer API calls require it.
3. Raise `maven.compiler.release` only if the new Paper line requires a newer Java runtime.
4. Compile, review API changes from official Paper sources, and perform the complete staging test.
5. Install the first raised-`api-version` build manually; automatic updates will intentionally reject it on the old channel.

## Known limitations and technical debt

- There is no automated real-Paper server test. Unit and packaged-JAR tests do not emulate the lighting engine or event ordering.
- Controller clicks currently rebuild contributions and rewrite the complete YAML state synchronously. The default radius is suitable for a private SMP, but performance work should separate definition persistence from the larger claim journal before supporting large-scale use.
- The model stores a controller UUID but not its expected block/chunk position. Removal paths that bypass hanging-break events can leave an active definition without a physical controller; avoid destructive auto-cleanup when the chunk may simply be unloaded.
- The updater queries GitHub's single `latest` release before applying the API-channel gate. Once a newer API channel becomes latest, old-channel servers may log a rejected update on each startup and will not discover an older compatible release.
- Vertical footprint edges are clipped at world build limits; only the selected origin and centre are rejected when out of bounds.
- Vanilla lighting is scalar: the optional `DUST` wash communicates color but cannot tint blocks or the actual light engine.

Do not hide these limitations in documentation or “fix” them by weakening world safety, persistence ordering, or updater validation.

## Change discipline

- Read `README.md`, `CONTRIBUTING.md`, `SECURITY.md`, and the affected tests before changing behaviour.
- Preserve unrelated user changes and the existing worktree. Do not commit, push, tag, stage, discard, or rewrite history unless the user explicitly authorizes it.
- Do not check in `target/`, server directories, world data, generated `state.yml`, secrets, tokens, logs containing private server details, or downloaded Paper/Minecraft JARs.
- Update documentation and `config.yml` comments with any user-visible or operator-visible behaviour change.
- Treat updater, release workflow, persistence, and world-restoration changes as security/reliability sensitive and request a focused review.
