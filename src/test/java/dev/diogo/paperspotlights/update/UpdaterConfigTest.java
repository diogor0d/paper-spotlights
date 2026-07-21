package dev.diogo.paperspotlights.update;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class UpdaterConfigTest {
    private static final String PLUGIN_NAME = "PaperSpotlights";
    private static final String MAIN_CLASS = "dev.diogo.paperspotlights.PaperSpotlightsPlugin";
    private static final String API_VERSION = "26.2";

    @Test
    void acceptsConservativeGitHubCoordinatesAndAssetName() {
        assertDoesNotThrow(() -> new UpdaterConfig(
                "diogo-7",
                "paper_spotlights.java",
                "PaperSpotlights-26.2.jar",
                32L * 1024L * 1024L,
                PLUGIN_NAME,
                MAIN_CLASS,
                API_VERSION,
                Duration.ofSeconds(5),
                Duration.ofSeconds(20)));
    }

    @Test
    void rejectsUnsafeCoordinatesAndFileNames() {
        assertThrows(IllegalArgumentException.class, () -> config("bad/owner", "repo", "plugin.jar"));
        assertThrows(IllegalArgumentException.class, () -> config("owner--name", "repo", "plugin.jar"));
        assertThrows(IllegalArgumentException.class, () -> config("owner", "../repo", "plugin.jar"));
        assertThrows(IllegalArgumentException.class, () -> config("owner", "repo", "../plugin.jar"));
        assertThrows(IllegalArgumentException.class, () -> config("owner", "repo", "sub/plugin.jar"));
        assertThrows(IllegalArgumentException.class, () -> config("owner", "repo", "plugin..jar"));
        assertThrows(IllegalArgumentException.class, () -> config("owner", "repo", "CON.jar"));
    }

    @Test
    void rejectsNonPositiveLimitsAndTimeouts() {
        assertThrows(IllegalArgumentException.class, () -> new UpdaterConfig(
                "owner", "repo", "plugin.jar", 0, PLUGIN_NAME, MAIN_CLASS, API_VERSION,
                Duration.ofSeconds(1), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new UpdaterConfig(
                "owner", "repo", "plugin.jar", 1, PLUGIN_NAME, MAIN_CLASS, API_VERSION,
                Duration.ZERO, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new UpdaterConfig(
                "owner", "repo", "plugin.jar", 1, PLUGIN_NAME, MAIN_CLASS, API_VERSION,
                Duration.ofSeconds(1), Duration.ofSeconds(-1)));
    }

    private static UpdaterConfig config(String owner, String repository, String assetName) {
        return new UpdaterConfig(
                owner, repository, assetName, 1024, PLUGIN_NAME, MAIN_CLASS, API_VERSION);
    }
}
