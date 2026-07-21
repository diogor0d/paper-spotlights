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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void indexesRelevantPositionsByWorldAndChunk() {
        BlockPosition sameChunk = new BlockPosition(WORLD, 15, 64, 15);
        BlockPosition anotherChunk = new BlockPosition(WORLD, 16, 64, 0);
        BlockPosition anotherWorld = new BlockPosition("minecraft:the_nether", 0, 64, 0);
        LightFieldService service = new LightFieldService();

        service.rebuild(List.of(
                spotlight("first", 9, true, sameChunk),
                spotlight("second", 9, true, anotherChunk),
                spotlight("third", 9, true, anotherWorld)
        ));

        assertEquals(Set.of(sameChunk), service.relevantPositions(WORLD, 0, 0));
        assertEquals(Set.of(anotherChunk), service.relevantPositions(WORLD, 1, 0));
        assertEquals(Set.of(), service.relevantPositions("minecraft:the_nether", 1, 0));
    }

    @Test
    void disabledDefinitionsReserveExistingClaimsUntilTheDefinitionIsRemoved() {
        Spotlight spotlight = spotlight("stage", 9, true, SHARED);
        LightFieldService service = new LightFieldService();

        service.rebuild(List.of(spotlight), List.of(spotlight));
        assertTrue(service.isReserved(SHARED));

        service.rebuild(List.of(spotlight), List.of());
        assertEquals(0, service.desiredLevel(SHARED));
        assertTrue(service.isReserved(SHARED));

        service.rebuild(List.of(), List.of());
        assertFalse(service.isReserved(SHARED));
    }

    @Test
    void incrementalSweepReturnsBoundedBatchesWithoutDroppingCoordinates() {
        BlockPosition first = new BlockPosition(WORLD, 0, 64, 0);
        BlockPosition second = new BlockPosition(WORLD, 16, 64, 0);
        BlockPosition third = new BlockPosition(WORLD, 32, 64, 0);
        LightFieldService service = new LightFieldService();

        service.rebuild(List.of(
                spotlight("first", 9, true, first),
                spotlight("second", 9, true, second),
                spotlight("third", 9, true, third)
        ));

        Set<BlockPosition> firstBatch = service.nextSweepBatch(2);
        Set<BlockPosition> secondBatch = service.nextSweepBatch(2);

        assertEquals(2, firstBatch.size());
        assertEquals(Set.of(first, second, third), union(firstBatch, secondBatch));
    }

    @Test
    void incrementalSweepDiscardsCoordinatesThatAreNoLongerRelevant() {
        LightFieldService service = new LightFieldService();
        service.rebuild(List.of(spotlight("temporary", 9, true, SHARED)));
        assertEquals(Set.of(SHARED), service.nextSweepBatch(1));

        service.rebuild(List.of());

        assertEquals(Set.of(), service.nextSweepBatch(1));
        assertEquals(Set.of(), service.relevantPositions(WORLD, 0, 0));
    }

    @Test
    void targetedUpdatePreservesUnrelatedAndOverlappingContributions() {
        Spotlight first = spotlight("first", 5, true, SHARED);
        Spotlight second = spotlight("second", 11, true, SHARED);
        Spotlight brighter = first.withIntensity(13);
        LightFieldService service = new LightFieldService();
        service.rebuild(List.of(first, second), List.of(first, second));

        service.updateSpotlight(first, true, brighter, true);
        assertEquals(13, service.desiredLevel(SHARED));
        assertTrue(service.isReserved(SHARED));

        service.updateSpotlight(brighter, true, null, false);
        assertEquals(11, service.desiredLevel(SHARED));
        assertTrue(service.isReserved(SHARED));

        service.updateSpotlight(second, true, null, false);
        assertEquals(0, service.desiredLevel(SHARED));
        assertFalse(service.isReserved(SHARED));
    }

    private static Set<BlockPosition> union(
            Set<BlockPosition> first,
            Set<BlockPosition> second
    ) {
        java.util.LinkedHashSet<BlockPosition> result = new java.util.LinkedHashSet<>(first);
        result.addAll(second);
        return Set.copyOf(result);
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
