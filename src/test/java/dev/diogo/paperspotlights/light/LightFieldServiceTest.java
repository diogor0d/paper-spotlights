package dev.diogo.paperspotlights.light;

import dev.diogo.paperspotlights.model.BlockPosition;
import dev.diogo.paperspotlights.model.Plane;
import dev.diogo.paperspotlights.model.Shape;
import dev.diogo.paperspotlights.model.Spotlight;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LightFieldServiceTest {

    private static final String WORLD = "minecraft:overworld";
    private static final BlockPosition SHARED = new BlockPosition(WORLD, 0, 64, 0);

    @Test
    void overlappingSpotlightsUseMaximumRatherThanAdding() {
        Spotlight dim = spotlight("dim", 5, true, SHARED);
        Spotlight bright = spotlight("bright", 13, true, SHARED);
        LightFieldService service = new LightFieldService();

        service.rebuild(List.of(dim, bright));

        assertEquals(13, service.desiredLevel(SHARED));
    }

    @Test
    void disablingOrRemovingOneOwnerKeepsTheOtherContribution() {
        Spotlight first = spotlight("first", 7, true, SHARED);
        Spotlight second = spotlight("second", 11, true, SHARED);
        LightFieldService service = new LightFieldService();

        service.rebuild(List.of(first, second));
        service.rebuild(List.of(first, second.withEnabled(false)));
        assertEquals(7, service.desiredLevel(SHARED));

        service.rebuild(List.of());
        assertEquals(0, service.desiredLevel(SHARED));
    }

    @Test
    void filtersUnrelatedWorldEventsBeforeTheyEnterTheWorkQueue() {
        Spotlight spotlight = spotlight("stage", 9, true, SHARED);
        BlockPosition unrelated = new BlockPosition(WORLD, 200, 64, 200);
        LightFieldService service = new LightFieldService();

        service.rebuild(List.of(spotlight));

        assertEquals(Set.of(SHARED), service.relevantPositions(List.of(SHARED, unrelated)));
    }

    private static Spotlight spotlight(
            String name,
            int intensity,
            boolean enabled,
            BlockPosition target
    ) {
        return new Spotlight(
                UUID.randomUUID(),
                name,
                target,
                target,
                Plane.XZ,
                Shape.CIRCLE,
                0,
                intensity,
                enabled,
                UUID.randomUUID()
        );
    }
}
