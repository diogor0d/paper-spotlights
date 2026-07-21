package dev.diogo.paperspotlights.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class SpotlightTest {

    @Test
    void withEnabledReturnsAnUpdatedImmutableValue() {
        Spotlight original = spotlight(true, 12);

        Spotlight updated = original.withEnabled(false);

        assertNotSame(original, updated);
        assertTrue(original.enabled());
        assertFalse(updated.enabled());
        assertEquals(original.id(), updated.id());
        assertEquals(original.intensity(), updated.intensity());
    }

    @Test
    void withIntensityReturnsAnUpdatedImmutableValue() {
        Spotlight original = spotlight(true, 12);

        Spotlight updated = original.withIntensity(7);

        assertNotSame(original, updated);
        assertEquals(12, original.intensity());
        assertEquals(7, updated.intensity());
        assertEquals(original.id(), updated.id());
        assertEquals(original.enabled(), updated.enabled());
    }

    @Test
    void intensityIsLimitedToMinecraftLightLevels() {
        assertThrows(IllegalArgumentException.class, () -> spotlight(true, -1));
        assertThrows(IllegalArgumentException.class, () -> spotlight(true, 16));
        assertThrows(IllegalArgumentException.class, () -> spotlight(true, 15).withIntensity(16));
    }

    private static Spotlight spotlight(boolean enabled, int intensity) {
        return new Spotlight(
                UUID.fromString("9d57c4d2-43cd-4b08-9d47-f92569cf4c64"),
                "Spawn stage",
                new BlockPosition("minecraft:overworld", 0, 70, 0),
                new BlockPosition("minecraft:overworld", 0, 64, 0),
                Plane.XZ,
                Shape.CIRCLE,
                4,
                intensity,
                enabled,
                UUID.fromString("f091279e-a9d2-4f07-87ea-700e6508fb86")
        );
    }
}
