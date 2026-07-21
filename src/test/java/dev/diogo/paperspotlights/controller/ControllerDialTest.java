package dev.diogo.paperspotlights.controller;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ControllerDialTest {

    @Test
    void cyclesThroughEightReadableLevels() {
        int level = 0;
        Set<Integer> seen = new HashSet<>();
        for (int count = 0; count < 8; count++) {
            level = ControllerDial.nextIntensity(level);
            seen.add(level);
        }

        assertEquals(Set.of(1, 3, 5, 7, 9, 11, 13, 15), seen);
        assertEquals(1, ControllerDial.nextIntensity(15));
    }

    @Test
    void closestDialPositionIsStableForArbitraryCommandLevels() {
        assertEquals(
                ControllerDial.rotationForIntensity(9),
                ControllerDial.rotationForIntensity(10)
        );
        assertEquals(
                ControllerDial.rotationForIntensity(13),
                ControllerDial.rotationForIntensity(14)
        );
    }
}
