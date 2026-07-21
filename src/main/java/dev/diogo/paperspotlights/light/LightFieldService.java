package dev.diogo.paperspotlights.light;

import dev.diogo.paperspotlights.model.BlockPosition;
import dev.diogo.paperspotlights.model.Spotlight;
import dev.diogo.paperspotlights.model.SpotlightGeometry;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Light;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Owns the coordinate-to-contributor index and is the only class that writes
 * vanilla LIGHT blocks. All methods must be called on the server thread.
 */
public final class LightFieldService {

    private final Map<BlockPosition, Map<UUID, Integer>> contributions = new HashMap<>();
    private final Map<BlockPosition, String> managedBaselines = new LinkedHashMap<>();

    public void loadManaged(Map<BlockPosition, String> loaded) {
        for (Map.Entry<BlockPosition, String> entry : loaded.entrySet()) {
            NamespacedKey worldKey = NamespacedKey.fromString(entry.getKey().worldKey());
            if (worldKey == null) {
                throw new IllegalArgumentException("Invalid world key at " + entry.getKey());
            }
            BlockData baseline = Bukkit.createBlockData(entry.getValue());
            if (!baseline.getMaterial().isAir()) {
                throw new IllegalArgumentException(
                        "Managed baseline is not air at " + entry.getKey() + ": " + entry.getValue()
                );
            }
        }
        managedBaselines.clear();
        managedBaselines.putAll(loaded);
    }

    /**
     * Replaces the desired contribution index and returns every coordinate
     * whose physical state may now be stale.
     */
    public Set<BlockPosition> rebuild(Collection<Spotlight> spotlights) {
        Set<BlockPosition> affected = new LinkedHashSet<>(contributions.keySet());
        contributions.clear();

        for (Spotlight spotlight : spotlights) {
            if (!spotlight.enabled() || spotlight.intensity() <= 0) {
                continue;
            }
            for (BlockPosition position : SpotlightGeometry.positions(spotlight)) {
                contributions.computeIfAbsent(position, ignored -> new HashMap<>())
                        .put(spotlight.id(), spotlight.intensity());
            }
        }
        affected.addAll(contributions.keySet());
        affected.addAll(managedBaselines.keySet());
        return Set.copyOf(affected);
    }

    /**
     * Records baselines for loaded AIR cells before any LIGHT block is placed.
     * Existing, unowned LIGHT blocks and ordinary blocks are deliberately left
     * untouched.
     */
    public Set<BlockPosition> prepareClaims(Collection<BlockPosition> positions) {
        Set<BlockPosition> added = new LinkedHashSet<>();
        for (BlockPosition position : positions) {
            if (desiredLevel(position) <= 0 || managedBaselines.containsKey(position)) {
                continue;
            }

            Block block = loadedBlock(position);
            if (block == null || !block.getType().isAir()) {
                continue;
            }
            managedBaselines.put(position, block.getBlockData().getAsString());
            added.add(position);
        }
        return Set.copyOf(added);
    }

    /** Rolls back claims that have not yet been durably committed. */
    public void rollbackClaims(Collection<BlockPosition> positions) {
        positions.forEach(managedBaselines::remove);
    }

    /**
     * Reconciles one coordinate. Unloaded chunks are ignored until their
     * ChunkLoadEvent. A real player/world block always wins over a managed
     * invisible light.
     */
    public ApplyResult applyOne(BlockPosition position) {
        Block block = loadedBlock(position);
        if (block == null) {
            return ApplyResult.UNLOADED;
        }

        int desired = desiredLevel(position);
        String baseline = managedBaselines.get(position);
        if (desired > 0) {
            if (baseline == null) {
                return ApplyResult.UNMANAGED;
            }
            if (!block.getType().isAir() && block.getType() != Material.LIGHT) {
                return ApplyResult.OCCUPIED;
            }

            Light currentLight = block.getType() == Material.LIGHT
                    ? (Light) block.getBlockData()
                    : null;
            Light lightData = (Light) Material.LIGHT.createBlockData();
            int clamped = Math.clamp(desired, lightData.getMinimumLevel(), lightData.getMaximumLevel());
            if (currentLight != null) {
                lightData.setWaterlogged(currentLight.isWaterlogged());
            }
            lightData.setLevel(clamped);
            if (currentLight != null && currentLight.getLevel() == clamped) {
                return ApplyResult.UNCHANGED;
            }
            block.setBlockData(lightData);
            return ApplyResult.LIGHT_CHANGED;
        }

        if (baseline == null) {
            return ApplyResult.UNCHANGED;
        }

        if (block.getType() == Material.LIGHT || block.getType().isAir()) {
            BlockData baselineData;
            if (block.getType() == Material.LIGHT
                    && ((Light) block.getBlockData()).isWaterlogged()) {
                baselineData = Material.WATER.createBlockData();
            } else {
                baselineData = Bukkit.createBlockData(baseline);
            }
            if (!block.getBlockData().matches(baselineData)) {
                block.setBlockData(baselineData);
            }
        }
        managedBaselines.remove(position);
        return ApplyResult.CLAIM_RELEASED;
    }

    public Set<BlockPosition> relevantPositions() {
        Set<BlockPosition> result = new LinkedHashSet<>(contributions.keySet());
        result.addAll(managedBaselines.keySet());
        return Set.copyOf(result);
    }

    /** Filters an event's coordinates down to desired or durably managed cells. */
    public Set<BlockPosition> relevantPositions(Collection<BlockPosition> candidates) {
        Set<BlockPosition> result = new LinkedHashSet<>();
        for (BlockPosition position : candidates) {
            if (contributions.containsKey(position) || managedBaselines.containsKey(position)) {
                result.add(position);
            }
        }
        return Set.copyOf(result);
    }

    /** Returns relevant coordinates whose worlds and chunks are currently loaded. */
    public Set<BlockPosition> relevantLoadedPositions() {
        Set<BlockPosition> result = new LinkedHashSet<>();
        for (BlockPosition position : relevantPositions()) {
            if (loadedBlock(position) != null) {
                result.add(position);
            }
        }
        return Set.copyOf(result);
    }

    public Set<BlockPosition> relevantPositions(String worldKey, int chunkX, int chunkZ) {
        Set<BlockPosition> result = new HashSet<>();
        for (BlockPosition position : relevantPositions()) {
            if (position.worldKey().equals(worldKey)
                    && position.x() >> 4 == chunkX
                    && position.z() >> 4 == chunkZ) {
                result.add(position);
            }
        }
        return Set.copyOf(result);
    }

    public Map<BlockPosition, String> managedSnapshot() {
        return Map.copyOf(managedBaselines);
    }

    /** Removes one visible, unowned LIGHT block selected explicitly with the lens. */
    public boolean removeUnmanagedLight(BlockPosition position) {
        if (managedBaselines.containsKey(position)) {
            return false;
        }
        Block block = loadedBlock(position);
        if (block == null || block.getType() != Material.LIGHT) {
            return false;
        }
        Light light = (Light) block.getBlockData();
        block.setBlockData((light.isWaterlogged() ? Material.WATER : Material.AIR).createBlockData());
        return true;
    }

    public int desiredLevel(BlockPosition position) {
        Map<UUID, Integer> owners = contributions.get(position);
        if (owners == null || owners.isEmpty()) {
            return 0;
        }
        int maximum = 0;
        for (int contribution : owners.values()) {
            maximum = Math.max(maximum, contribution);
        }
        return maximum;
    }

    private static Block loadedBlock(BlockPosition position) {
        NamespacedKey worldKey = NamespacedKey.fromString(position.worldKey());
        if (worldKey == null) {
            return null;
        }
        World world = Bukkit.getWorld(worldKey);
        if (world == null
                || position.y() < world.getMinHeight()
                || position.y() >= world.getMaxHeight()
                || !world.isChunkLoaded(position.x() >> 4, position.z() >> 4)) {
            return null;
        }
        return world.getBlockAt(position.x(), position.y(), position.z());
    }

    public enum ApplyResult {
        LIGHT_CHANGED,
        CLAIM_RELEASED,
        OCCUPIED,
        UNMANAGED,
        UNLOADED,
        UNCHANGED
    }
}
