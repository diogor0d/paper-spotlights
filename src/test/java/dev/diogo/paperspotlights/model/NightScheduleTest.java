package dev.diogo.paperspotlights.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NightScheduleTest {

    @Test
    void usesTheFullyDarkNightInterval() {
        assertFalse(NightSchedule.isNight(12_999));
        assertTrue(NightSchedule.isNight(13_000));
        assertTrue(NightSchedule.isNight(22_999));
        assertFalse(NightSchedule.isNight(23_000));
    }

    @Test
    void normalizesRelativeTimeAcrossDays() {
        assertTrue(NightSchedule.isNight(37_000));
        assertFalse(NightSchedule.isNight(-1_000));
    }
}
