package dev.diogo.paperspotlights.controller;

import org.bukkit.Rotation;

import java.util.List;

/** Maps the eight vanilla item-frame rotations to useful light levels. */
public final class ControllerDial {

    private static final List<Rotation> ROTATIONS = List.of(
            Rotation.NONE,
            Rotation.CLOCKWISE_45,
            Rotation.CLOCKWISE,
            Rotation.CLOCKWISE_135,
            Rotation.FLIPPED,
            Rotation.FLIPPED_45,
            Rotation.COUNTER_CLOCKWISE,
            Rotation.COUNTER_CLOCKWISE_45
    );
    private static final List<Integer> LEVELS = List.of(1, 3, 5, 7, 9, 11, 13, 15);

    private ControllerDial() {
    }

    public static int nextIntensity(int current) {
        for (int level : LEVELS) {
            if (level > current) {
                return level;
            }
        }
        return LEVELS.getFirst();
    }

    public static Rotation rotationForIntensity(int intensity) {
        int bestIndex = 0;
        int bestDistance = Integer.MAX_VALUE;
        for (int index = 0; index < LEVELS.size(); index++) {
            int distance = Math.abs(LEVELS.get(index) - intensity);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = index;
            }
        }
        return ROTATIONS.get(bestIndex);
    }
}

