package dev.diogo.paperspotlights.update;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PackagedArtifactIT {

    @Test
    void builtJarPassesTheSameIdentityAndApiChannelGateAsAnAutomaticUpdate() throws Exception {
        Path jar = Path.of("target", "PaperSpotlights.jar");
        assertTrue(Files.isRegularFile(jar));

        SemVer version = SemVer.parseTag("v" + System.getProperty("projectVersion"));
        GitHubRelease release = new GitHubRelease(
                version.tag(),
                version,
                new GitHubRelease.Asset(
                        1,
                        "PaperSpotlights.jar",
                        OptionalLong.of(Files.size(jar)),
                        "0".repeat(64)
                )
        );
        UpdaterConfig config = new UpdaterConfig(
                "diogo",
                "paper-spotlights",
                "PaperSpotlights.jar",
                16L * 1024L * 1024L,
                "PaperSpotlights",
                "dev.diogo.paperspotlights.PaperSpotlightsPlugin",
                "26.2"
        );

        PluginJarValidator.validate(jar, release, config);
    }
}
