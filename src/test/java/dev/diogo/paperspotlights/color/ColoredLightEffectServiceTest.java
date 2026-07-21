package dev.diogo.paperspotlights.color;

import dev.diogo.paperspotlights.model.BlockPosition;
import dev.diogo.paperspotlights.model.Plane;
import dev.diogo.paperspotlights.model.Shape;
import dev.diogo.paperspotlights.model.SpotlightColor;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ColoredLightEffectServiceTest {

    private static final String WORLD = "minecraft:overworld";

    @Test
    void particleCountIsSmallAndTracksRadiusAndIntensity() {
        assertEquals(2, ColoredLightEffectService.particleCount(1, 1));
        assertEquals(4, ColoredLightEffectService.particleCount(1, 15));
        assertEquals(12, ColoredLightEffectService.particleCount(32, 15));
    }

    @Test
    void effectRejectsCrossWorldCoordinates() {
        BlockPosition origin = new BlockPosition(WORLD, 0, 70, 0);
        BlockPosition target = new BlockPosition("minecraft:the_nether", 0, 64, 0);

        assertThrows(IllegalArgumentException.class, () -> new ColoredLightEffectService.Effect(
                UUID.randomUUID(),
                origin,
                target,
                Plane.XZ,
                Shape.CIRCLE,
                4,
                15,
                true,
                SpotlightColor.RED
        ));
    }
}
