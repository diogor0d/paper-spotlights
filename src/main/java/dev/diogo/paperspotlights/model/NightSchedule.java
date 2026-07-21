package dev.diogo.paperspotlights.model;

/** Defines the nightly interval used by automatic spotlights. */
public final class NightSchedule {

    public static final long TICKS_PER_DAY = 24_000L;
    public static final long DUSK_TICK = 13_000L;
    public static final long DAWN_TICK = 23_000L;

    private NightSchedule() {
    }

    /**
     * Returns whether a relative world time is within the fully dark nightly
     * interval: [13,000, 23,000).
     */
    public static boolean isNight(long worldTime) {
        long timeOfDay = Math.floorMod(worldTime, TICKS_PER_DAY);
        return timeOfDay >= DUSK_TICK && timeOfDay < DAWN_TICK;
    }
}
