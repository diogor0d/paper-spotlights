# PaperSpotlights

> Minecraft-native, player-built dimmable stage lighting for Paper.

PaperSpotlights turns vanilla invisible `LIGHT` blocks into controllable circle or square lighting areas. Players build any fixture they want, aim it with an in-game setup lens, and operate it from a clock mounted in an item frame—no resource pack, client mod, or companion plugin required.

**Target:** Paper 26.2 · Java 25 · Private/trusted SMPs · No permissions by design

> **Development status:** `0.1.0` release candidate. The project passes its automated build and packaged-JAR checks, but the first public release should not be published until the real-Paper staging checklist in [CONTRIBUTING.md](CONTRIBUTING.md) is complete. The repository currently pins the beta API build `26.2.build.62-beta`; Paper listed 26.2 as experimental on 2026-07-21. See [Paper downloads](https://papermc.io/downloads/paper/) and [Paper's project setup guide](https://docs.papermc.io/paper/dev/project-setup/).

## Highlights

- **Feels like vanilla:** invisible native light blocks, vanilla falloff, physical clock controllers, and no custom textures.
- **Simple setup:** select the fixture origin, target surface, and controller; one command creates the light.
- **Useful shapes:** filled circles or squares on floors, ceilings, and walls.
- **Real dimming:** eight clock-dial positions from light level 1 to 15, plus toggle without losing the chosen level.
- **Predictable world handling:** ordinary blocks and existing fluids win, overlaps use the brightest active level, and distant chunks are never force-loaded.
- **Crash-conscious state:** managed cells and definitions are persisted before relevant world changes, with a previous-state backup for manual recovery.
- **Conservative updates:** optional GitHub Releases checks verify the digest and plugin identity, then stage an update for the next restart.
- **Low version coupling:** public Paper/Bukkit APIs only—no NMS, CraftBukkit internals, reflection, packets, or version-specific adapters.

PaperSpotlights creates a filled emitter footprint with normal Minecraft light propagation. It is not a rendered cone or a hard-edged photometric mask.

## Compatibility

| Requirement | Value |
|---|---|
| Server | Paper 26.2 |
| Java | 25 |
| Client mods or resource pack | None |
| Other plugins | None |
| Permissions plugin | Not used |
| Intended environment | Trusted/private SMP |

Because `plugin.yml` declares API version 26.2, older servers will correctly refuse this build. Treat the current target as pre-stable upstream and use a backup plus staging server.

## Player workflow

1. Build any fixture and put a clock in an item frame wherever its dimmer should live.
2. Run `/spotlight wand` or `/sl wand` to receive the **Gaffer's Lens**.
3. Hold the lens and select:
   - **Left-click** a fixture face for the beam origin.
   - **Right-click** the surface centre to illuminate.
   - **Right-click** the clock item frame to assign the controller.
4. Create the spotlight, for example:

```text
/sl create stage-left circle 6
```

The clicked target face selects the plane automatically: floors and ceilings use X/Z, north/south walls use X/Y, and east/west walls use Z/Y.

### Clock controller

| Interaction | Result |
|---|---|
| Right-click | Advance through `1, 3, 5, 7, 9, 11, 13, 15`. |
| Sneak-right-click | Toggle power while remembering the selected level. |
| Break the item frame | Remove the spotlight and its managed lights. |

Holding the Gaffer's Lens reveals vanilla invisible `LIGHT` blocks. Sneak-left-clicking a visible light removes it only when PaperSpotlights does not manage it. This cleanup is destructive and cannot identify lights owned by another plugin, so use it carefully on mixed-plugin servers.

## Commands

| Command | Purpose |
|---|---|
| `/sl wand` | Get the setup and maintenance lens. |
| `/sl create <name> <circle\|square> <radius>` | Create a spotlight from the current selections. |
| `/sl cancel` | Clear the current setup selections. |
| `/sl list` | List spotlights and their state. |
| `/sl info <name>` | Show details and a short particle preview. |
| `/sl toggle <name>` | Toggle without the physical controller. |
| `/sl level <name> <1-15>` | Set an exact light level. |
| `/sl remove <name>` | Remove the spotlight while keeping its item frame. |

There are intentionally no permission nodes. Any player can create, operate, or remove a spotlight.

## Installation

No public release has been published yet. Build the current release candidate from source until the first staging-tested GitHub Release is available.

### Build from source

Install JDK 25 and Git. A separate Maven installation is not required because the repository includes a checksum-pinned Maven Wrapper.

Windows:

```powershell
.\mvnw.cmd clean verify
```

Linux/macOS:

```bash
bash ./mvnw clean verify
```

The release-ready artifact is `target/PaperSpotlights.jar`.

### Install on Paper

1. Copy `PaperSpotlights.jar` into the server's `plugins/` directory.
2. Start or restart Paper 26.2.
3. Confirm the plugin enables successfully before creating lights.
4. If automatic updates are wanted, stop the server and configure the repository as shown below.

Once the first release exists, operators can download the same `PaperSpotlights.jar` asset from this repository's GitHub Releases page instead of building it.

## Lighting behaviour

- A circle or square is a filled emitter footprint. Vanilla light continues outside it and is blocked naturally by world geometry.
- The selected origin is also lit so the player-built fixture visibly emits light.
- The plugin initially claims only air. Existing builds, plants, water, and unowned lights are not replaced.
- If a managed light later becomes waterlogged, changing intensity preserves that state and disabling or removing it restores water.
- If a real block occupies a managed coordinate, the block wins. An active light can return after the cell becomes air again.
- Overlapping spotlights resolve to the highest active level; disabling one does not erase another owner's light.
- Changes are coalesced and processed over multiple ticks. Unloaded chunks reconcile when loaded and are never force-loaded.
- Footprints crossing the world's vertical limits are clipped; the selected origin and centre must themselves be in bounds.

The default maximum radius is 16. A square of radius `r` uses `(2r + 1)^2` emitters, so larger limits have a real lighting-engine and persistence cost.

## Configuration

PaperSpotlights creates `plugins/PaperSpotlights/config.yml`:

```yaml
max-radius: 16
changes-per-tick: 128

updates:
  enabled: true
  repository: ''
  asset-name: PaperSpotlights.jar
  max-size-mib: 16
```

- `max-radius` accepts `1-32`; 16 is the recommended private-SMP default.
- `changes-per-tick` accepts `16-2048` and bounds block reconciliation work per server tick.
- `updates.max-size-mib` accepts `1-64` and caps release-asset downloads.
- A blank `updates.repository` disables network requests even when `enabled` is true.
- Configuration changes require a restart. Runtime reload support is deliberately omitted for persistent world state.

## Automatic updates

Set the public GitHub repository after the server has generated its configuration:

```yaml
updates:
  enabled: true
  repository: 'OWNER/paper-spotlights'
  asset-name: PaperSpotlights.jar
  max-size-mib: 16
```

At startup, the updater asynchronously checks the latest non-draft, non-prerelease GitHub Release. A newer asset is accepted only when all of these checks pass:

- Strict `vMAJOR.MINOR.PATCH` versioning.
- Exactly one asset with the configured name and an acceptable size.
- GitHub's SHA-256 asset digest.
- Expected plugin name and main class, with the main class present in the JAR.
- Plugin version matching the release tag.
- The same Paper `api-version` as the running installation.

The verified JAR is staged in Paper's configured update directory and is applied on the **next restart**. The running plugin is never overwritten or hot-reloaded.

The exact API-channel check intentionally blocks automatic migration to a newer Paper API. Install and smoke-test the first build on a raised `api-version` manually; later builds on that channel can update automatically.

Digest verification detects corruption or an asset mismatch, but it cannot make a malicious release safe. Anyone able to publish in the configured repository can publish server code. Protect the repository with 2FA, protected tags, and reviewed workflows; see [SECURITY.md](SECURITY.md) for the trust model. Paper also recommends backups and cautions that automatic updates can introduce conflicts in its [updating guide](https://docs.papermc.io/paper/updating/).

## Safe operation and recovery

### Removing the plugin

Remove every spotlight while its area chunks are loaded, then wait a few seconds for batched cleanup before uninstalling the JAR. Removing a light-management plugin before it cleans unloaded chunks can leave invisible vanilla lights behind.

If an item frame is removed by an external command or plugin without a normal hanging-break event, its definition may remain active. Use `/sl list`, `/sl info`, and `/sl remove` to reconcile it manually.

### Recovering state

Runtime state lives in `plugins/PaperSpotlights/state.yml`. Saves retain the previous state as `state.yml.bak` and use an atomic replacement where the filesystem supports it.

If startup reports invalid or unsupported state, PaperSpotlights disables itself without changing the world. Stop the server, preserve both files, copy the broken primary elsewhere, and only then restore `state.yml.bak` manually. The backup is never loaded silently because it may be stale. Verify every affected area before deleting the preserved files.

## Development

`clean verify` is the release gate. It compiles with Java warnings treated as errors, runs unit tests, packages the plugin, and validates the actual built JAR with the same identity/API checks used by the updater.

- Read [CONTRIBUTING.md](CONTRIBUTING.md) before proposing changes.
- Future coding agents and maintainers should also read [AGENTS.md](AGENTS.md).
- Report suspected vulnerabilities through the process in [SECURITY.md](SECURITY.md).

## License

No project license has been selected yet. Until a `LICENSE` file is added, do not assume permission to redistribute or publish modified copies. Select a license before actively accepting outside contributions.
