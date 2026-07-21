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
import java.util.ArrayDeque;
import java.util.Deque;
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
    private final Map<BlockPosition, Set<UUID>> reservations = new HashMap<>();
    private final Map<BlockPosition, String> managedBaselines = new LinkedHashMap<>();
    private final Map<ChunkKey, Set<BlockPosition>> relevantByChunk = new HashMap<>();
    private final Deque<BlockPosition> sweepOrder = new ArrayDeque<>();
    private final Set<BlockPosition> scheduledForSweep = new HashSet<>();

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
        rebuildRelevantChunkIndex();
    }

    /**
     * Replaces the desired contribution index and returns every coordinate
     * whose physical state may now be stale.
     */
    public Set<BlockPosition> rebuild(Collection<Spotlight> spotlights) {
        return rebuild(spotlights, spotlights);
    }

    /**
     * Replaces the complete reservation and currently effective contribution
     * indexes. A reservation keeps an existing managed baseline claim while a
     * spotlight is disabled or outside its night schedule; only removing the
     * last definition that reserves a cell permits that claim to be released.
     */
    public Set<BlockPosition> rebuild(
            Collection<Spotlight> definitions,
            Collection<Spotlight> effectiveSpotlights
    ) {
        Set<BlockPosition> affected = new LinkedHashSet<>(contributions.keySet());
        affected.addAll(reservations.keySet());
        contributions.clear();
        reservations.clear();

        for (Spotlight spotlight : definitions) {
            for (BlockPosition position : SpotlightGeometry.positions(spotlight)) {
                reservations.computeIfAbsent(position, ignored -> new HashSet<>())
                        .add(spotlight.id());
            }
        }

        for (Spotlight spotlight : effectiveSpotlights) {
            if (!spotlight.enabled() || spotlight.intensity() <= 0) {
                continue;
            }
            for (BlockPosition position : SpotlightGeometry.positions(spotlight)) {
                contributions.computeIfAbsent(position, ignored -> new HashMap<>())
                        .put(spotlight.id(), spotlight.intensity());
            }
        }
        affected.addAll(contributions.keySet());
        affected.addAll(reservations.keySet());
        affected.addAll(managedBaselines.keySet());
        rebuildRelevantChunkIndex();
        return Set.copyOf(affected);
    }

    /**
     * Replaces one definition and its optional active contribution without
     * regenerating unrelated spotlight geometry.
     */
    public Set<BlockPosition> updateSpotlight(
            Spotlight previous,
            boolean previousEffective,
            Spotlight replacement,
            boolean replacementEffective
    ) {
        if (previous == null && replacement == null) {
            throw new IllegalArgumentException("previous and replacement cannot both be null");
        }
        if (previous != null && replacement != null && !previous.id().equals(replacement.id())) {
            throw new IllegalArgumentException("replacement must preserve the spotlight id");
        }

        Set<BlockPosition> affected = new LinkedHashSet<>();
        if (previous != null) {
            for (BlockPosition position : SpotlightGeometry.positions(previous)) {
                affected.add(position);
                removeReservation(position, previous.id());
                if (previousEffective) {
                    removeContribution(position, previous.id());
                }
            }
        }
        if (replacement != null) {
            for (BlockPosition position : SpotlightGeometry.positions(replacement)) {
                affected.add(position);
                reservations.computeIfAbsent(position, ignored -> new HashSet<>())
                        .add(replacement.id());
                if (replacementEffective && replacement.enabled() && replacement.intensity() > 0) {
                    contributions.computeIfAbsent(position, ignored -> new HashMap<>())
                            .put(replacement.id(), replacement.intensity());
                }
            }
        }
        affected.forEach(this::updateRelevantChunkIndex);
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
            updateRelevantChunkIndex(position);
            added.add(position);
        }
        return Set.copyOf(added);
    }

    /** Rolls back claims that have not yet been durably committed. */
    public void rollbackClaims(Collection<BlockPosition> positions) {
        for (BlockPosition position : positions) {
            managedBaselines.remove(position);
            updateRelevantChunkIndex(position);
        }
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

        boolean reserved = reservations.containsKey(position);
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
        if (reserved) {
            return ApplyResult.LIGHT_CLEARED;
        }
        managedBaselines.remove(position);
        updateRelevantChunkIndex(position);
        return ApplyResult.CLAIM_RELEASED;
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

    public Set<BlockPosition> relevantPositions(String worldKey, int chunkX, int chunkZ) {
        Set<BlockPosition> positions = relevantByChunk.get(new ChunkKey(worldKey, chunkX, chunkZ));
        return positions == null ? Set.of() : Set.copyOf(positions);
    }

    /**
     * Returns at most {@code maximum} relevant coordinates from a persistent,
     * fair sweep cursor. It neither builds nor scans the full coordinate set;
     * callers may filter the returned positions for loaded chunks as needed.
     */
    public Set<BlockPosition> nextSweepBatch(int maximum) {
        if (maximum < 1) {
            throw new IllegalArgumentException("maximum must be positive");
        }

        Set<BlockPosition> result = new LinkedHashSet<>();
        int examined = 0;
        while (examined < maximum && !sweepOrder.isEmpty()) {
            BlockPosition position = sweepOrder.removeFirst();
            scheduledForSweep.remove(position);
            examined++;
            if (!isRelevant(position)) {
                continue;
            }
            result.add(position);
            scheduleForSweep(position);
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

    boolean isReserved(BlockPosition position) {
        return reservations.containsKey(position);
    }

    private void rebuildRelevantChunkIndex() {
        relevantByChunk.clear();
        for (BlockPosition position : contributions.keySet()) {
            updateRelevantChunkIndex(position);
        }
        for (BlockPosition position : managedBaselines.keySet()) {
            updateRelevantChunkIndex(position);
        }
    }

    private void updateRelevantChunkIndex(BlockPosition position) {
        ChunkKey chunk = ChunkKey.from(position);
        if (isRelevant(position)) {
            relevantByChunk.computeIfAbsent(chunk, ignored -> new LinkedHashSet<>()).add(position);
            scheduleForSweep(position);
            return;
        }

        Set<BlockPosition> positions = relevantByChunk.get(chunk);
        if (positions == null) {
            return;
        }
        positions.remove(position);
        if (positions.isEmpty()) {
            relevantByChunk.remove(chunk);
        }
    }

    private boolean isRelevant(BlockPosition position) {
        return contributions.containsKey(position) || managedBaselines.containsKey(position);
    }

    private void scheduleForSweep(BlockPosition position) {
        if (scheduledForSweep.add(position)) {
            sweepOrder.addLast(position);
        }
    }

    private void removeReservation(BlockPosition position, UUID owner) {
        Set<UUID> owners = reservations.get(position);
        if (owners == null) {
            return;
        }
        owners.remove(owner);
        if (owners.isEmpty()) {
            reservations.remove(position);
        }
    }

    private void removeContribution(BlockPosition position, UUID owner) {
        Map<UUID, Integer> owners = contributions.get(position);
        if (owners == null) {
            return;
        }
        owners.remove(owner);
        if (owners.isEmpty()) {
            contributions.remove(position);
        }
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
        LIGHT_CLEARED,
        CLAIM_RELEASED,
        OCCUPIED,
        UNMANAGED,
        UNLOADED,
        UNCHANGED
    }

    private record ChunkKey(String worldKey, int x, int z) {

        private static ChunkKey from(BlockPosition position) {
            return new ChunkKey(position.worldKey(), position.x() >> 4, position.z() >> 4);
        }
    }
}
