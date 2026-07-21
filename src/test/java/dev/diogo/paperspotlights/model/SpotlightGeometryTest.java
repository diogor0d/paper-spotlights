package dev.diogo.paperspotlights.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class SpotlightGeometryTest {

    private static final String WORLD = "minecraft:overworld";

    @Test
    void circleUsesFilledEuclideanRadius() {
        BlockPosition target = position(10, 64, 20);

        Set<BlockPosition> positions = SpotlightGeometry.positions(
                position(10, 70, 20), target, Plane.XZ, Shape.CIRCLE, 2
        );

        assertEquals(14, positions.size()); // 13 circle blocks plus a distinct origin.
        assertTrue(positions.contains(position(12, 64, 20)));
        assertTrue(positions.contains(position(11, 64, 21)));
        assertFalse(positions.contains(position(12, 64, 22)));
    }

    @Test
    void squareCoversEveryCoordinateInItsBounds() {
        Set<BlockPosition> positions = SpotlightGeometry.positions(
                position(0, 5, 0), position(0, 0, 0), Plane.XZ, Shape.SQUARE, 2
        );

        assertEquals(26, positions.size()); // 5 x 5 square plus a distinct origin.
        assertTrue(positions.contains(position(-2, 0, -2)));
        assertTrue(positions.contains(position(2, 0, 2)));
    }

    @Test
    void xzPlaneChangesXAndZOnly() {
        Set<BlockPosition> positions = SpotlightGeometry.positions(
                position(10, 20, 30), position(10, 20, 30), Plane.XZ, Shape.SQUARE, 1
        );

        assertEquals(9, positions.size());
        assertTrue(positions.contains(position(9, 20, 29)));
        assertTrue(positions.stream().allMatch(position -> position.y() == 20));
    }

    @Test
    void xyPlaneChangesXAndYOnly() {
        Set<BlockPosition> positions = SpotlightGeometry.positions(
                position(10, 20, 30), position(10, 20, 30), Plane.XY, Shape.SQUARE, 1
        );

        assertEquals(9, positions.size());
        assertTrue(positions.contains(position(9, 19, 30)));
        assertTrue(positions.stream().allMatch(position -> position.z() == 30));
    }

    @Test
    void zyPlaneChangesZAndYOnly() {
        Set<BlockPosition> positions = SpotlightGeometry.positions(
                position(10, 20, 30), position(10, 20, 30), Plane.ZY, Shape.SQUARE, 1
        );

        assertEquals(9, positions.size());
        assertTrue(positions.contains(position(10, 19, 29)));
        assertTrue(positions.stream().allMatch(position -> position.x() == 10));
    }

    @Test
    void originIsAddedOnlyOnceWhenItIsInsideTheArea() {
        BlockPosition originAndTarget = position(1, 2, 3);

        Set<BlockPosition> positions = SpotlightGeometry.positions(
                originAndTarget, originAndTarget, Plane.XZ, Shape.CIRCLE, 0
        );

        assertEquals(Set.of(originAndTarget), positions);
    }

    @Test
    void rejectsNegativeRadius() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SpotlightGeometry.positions(
                        position(0, 0, 0), position(0, 0, 0), Plane.XZ, Shape.CIRCLE, -1
                )
        );
    }

    @Test
    void resultCannotBeMutated() {
        Set<BlockPosition> positions = SpotlightGeometry.positions(
                position(0, 0, 0), position(0, 0, 0), Plane.XZ, Shape.CIRCLE, 0
        );

        assertThrows(UnsupportedOperationException.class, () -> positions.clear());
    }

    private static BlockPosition position(int x, int y, int z) {
        return new BlockPosition(WORLD, x, y, z);
    }
}
