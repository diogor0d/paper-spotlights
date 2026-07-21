package dev.diogo.paperspotlights.model;

import java.util.Objects;

/**
 * An immutable integer block coordinate in a specific world.
 */
public record BlockPosition(String worldKey, int x, int y, int z) {

    public BlockPosition {
        Objects.requireNonNull(worldKey, "worldKey");
        if (worldKey.isBlank()) {
            throw new IllegalArgumentException("worldKey must not be blank");
        }
    }
}
