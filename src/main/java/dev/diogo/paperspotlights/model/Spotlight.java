package dev.diogo.paperspotlights.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable persisted configuration for one spotlight.
 */
public record Spotlight(
        UUID id,
        String name,
        BlockPosition origin,
        BlockPosition target,
        Plane plane,
        Shape shape,
        int radius,
        int intensity,
        boolean enabled,
        boolean nightOnly,
        SpotlightColor color,
        UUID controllerUuid
) {

    public static final int MIN_INTENSITY = 0;
    public static final int MAX_INTENSITY = 15;

    public Spotlight {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(plane, "plane");
        Objects.requireNonNull(shape, "shape");
        Objects.requireNonNull(color, "color");
        Objects.requireNonNull(controllerUuid, "controllerUuid");

        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (!origin.worldKey().equals(target.worldKey())) {
            throw new IllegalArgumentException("origin and target must be in the same world");
        }
        if (radius < 0) {
            throw new IllegalArgumentException("radius must be nonnegative");
        }
        validateIntensity(intensity);
    }

    /** Compatibility constructor for uncoloured, manually controlled spotlights. */
    public Spotlight(
            UUID id,
            String name,
            BlockPosition origin,
            BlockPosition target,
            Plane plane,
            Shape shape,
            int radius,
            int intensity,
            boolean enabled,
            UUID controllerUuid
    ) {
        this(
                id,
                name,
                origin,
                target,
                plane,
                shape,
                radius,
                intensity,
                enabled,
                false,
                SpotlightColor.NONE,
                controllerUuid
        );
    }

    public Spotlight withEnabled(boolean newEnabled) {
        return new Spotlight(
                id,
                name,
                origin,
                target,
                plane,
                shape,
                radius,
                intensity,
                newEnabled,
                nightOnly,
                color,
                controllerUuid
        );
    }

    public Spotlight withIntensity(int newIntensity) {
        validateIntensity(newIntensity);
        return new Spotlight(
                id,
                name,
                origin,
                target,
                plane,
                shape,
                radius,
                newIntensity,
                enabled,
                nightOnly,
                color,
                controllerUuid
        );
    }

    public Spotlight withNightOnly(boolean newNightOnly) {
        return new Spotlight(
                id,
                name,
                origin,
                target,
                plane,
                shape,
                radius,
                intensity,
                enabled,
                newNightOnly,
                color,
                controllerUuid
        );
    }

    public Spotlight withColor(SpotlightColor newColor) {
        return new Spotlight(
                id,
                name,
                origin,
                target,
                plane,
                shape,
                radius,
                intensity,
                enabled,
                nightOnly,
                Objects.requireNonNull(newColor, "newColor"),
                controllerUuid
        );
    }

    public boolean isEffectivelyEnabled(boolean night) {
        return enabled && (!nightOnly || night);
    }

    private static void validateIntensity(int value) {
        if (value < MIN_INTENSITY || value > MAX_INTENSITY) {
            throw new IllegalArgumentException(
                    "intensity must be between " + MIN_INTENSITY + " and " + MAX_INTENSITY
            );
        }
    }
}
