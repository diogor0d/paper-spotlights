package dev.diogo.paperspotlights.model;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Computes the blocks occupied by a spotlight without depending on a server API.
 */
public final class SpotlightGeometry {

    private SpotlightGeometry() {
    }

    public static Set<BlockPosition> positions(Spotlight spotlight) {
        Objects.requireNonNull(spotlight, "spotlight");
        return positions(
                spotlight.origin(),
                spotlight.target(),
                spotlight.plane(),
                spotlight.shape(),
                spotlight.radius()
        );
    }

    public static Set<BlockPosition> positions(
            BlockPosition origin,
            BlockPosition target,
            Plane plane,
            Shape shape,
            int radius
    ) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(plane, "plane");
        Objects.requireNonNull(shape, "shape");
        if (radius < 0) {
            throw new IllegalArgumentException("radius must be nonnegative");
        }

        Set<BlockPosition> positions = new LinkedHashSet<>();
        for (int u = -radius; u <= radius; u++) {
            for (int v = -radius; v <= radius; v++) {
                if (shape == Shape.CIRCLE && ((long) u * u + (long) v * v > (long) radius * radius)) {
                    continue;
                }
                positions.add(offset(target, plane, u, v));
            }
        }

        positions.add(origin);
        return Collections.unmodifiableSet(positions);
    }

    private static BlockPosition offset(BlockPosition target, Plane plane, int u, int v) {
        return switch (plane) {
            case XZ -> new BlockPosition(
                    target.worldKey(),
                    target.x() + u,
                    target.y(),
                    target.z() + v
            );
            case XY -> new BlockPosition(
                    target.worldKey(),
                    target.x() + u,
                    target.y() + v,
                    target.z()
            );
            case ZY -> new BlockPosition(
                    target.worldKey(),
                    target.x(),
                    target.y() + v,
                    target.z() + u
            );
        };
    }
}
