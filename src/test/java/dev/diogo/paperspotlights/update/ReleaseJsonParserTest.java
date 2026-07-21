package dev.diogo.paperspotlights.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ReleaseJsonParserTest {
    private static final String DIGEST_UPPER = "A".repeat(64);

    @Test
    void selectsOnlyTheExactConfiguredAssetAndNormalizesDigest() throws Exception {
        String json = """
                {
                  "tag_name": "v2.4.0-rc.1",
                  "assets": [
                    null,
                    {"id": 1, "name": "PaperSpotlights.jar.asc", "digest": null, "size": 12},
                    {"id": 42, "name": "PaperSpotlights.jar", "digest": "sha256:%s", "size": 99}
                  ]
                }
                """.formatted(DIGEST_UPPER);

        GitHubRelease release = ReleaseJsonParser.parse(json, "PaperSpotlights.jar", 100);

        assertEquals("v2.4.0-rc.1", release.version().tag());
        assertEquals(42, release.asset().id());
        assertEquals("a".repeat(64), release.asset().sha256Hex());
        assertEquals(99, release.asset().sizeBytes().orElseThrow());
    }

    @Test
    void reportsMissingAndAmbiguousExactAssetNames() {
        String missing = """
                {"tag_name":"v1.0.0","assets":[
                  {"id":1,"name":"other.jar","digest":"sha256:%s"}
                ]}
                """.formatted("0".repeat(64));
        UpdaterException missingFailure = assertThrows(
                UpdaterException.class,
                () -> ReleaseJsonParser.parse(missing, "plugin.jar", 1024));
        assertEquals(UpdateFailure.ASSET_NOT_FOUND, missingFailure.failure());

        String ambiguous = """
                {"tag_name":"v1.0.0","assets":[
                  {"id":1,"name":"plugin.jar","digest":"sha256:%s"},
                  {"id":2,"name":"plugin.jar","digest":"sha256:%s"}
                ]}
                """.formatted("0".repeat(64), "1".repeat(64));
        UpdaterException ambiguousFailure = assertThrows(
                UpdaterException.class,
                () -> ReleaseJsonParser.parse(ambiguous, "plugin.jar", 1024));
        assertEquals(UpdateFailure.ASSET_AMBIGUOUS, ambiguousFailure.failure());
    }

    @Test
    void rejectsDuplicateJsonKeysAndMalformedAssetSecurityFields() {
        String duplicateKey = """
                {"tag_name":"v1.0.0","tag_name":"v2.0.0","assets":[]}
                """;
        UpdaterException duplicateFailure = assertThrows(
                UpdaterException.class,
                () -> ReleaseJsonParser.parse(duplicateKey, "plugin.jar", 1024));
        assertEquals(UpdateFailure.RELEASE_FORMAT_INVALID, duplicateFailure.failure());

        String badDigest = """
                {"tag_name":"v1.0.0","assets":[
                  {"id":42,"name":"plugin.jar","digest":"sha256:1234","size":5}
                ]}
                """;
        UpdaterException digestFailure = assertThrows(
                UpdaterException.class,
                () -> ReleaseJsonParser.parse(badDigest, "plugin.jar", 1024));
        assertEquals(UpdateFailure.RELEASE_FORMAT_INVALID, digestFailure.failure());

        String fractionalId = """
                {"tag_name":"v1.0.0","assets":[
                  {"id":4.2,"name":"plugin.jar","digest":"sha256:%s","size":5}
                ]}
                """.formatted("0".repeat(64));
        UpdaterException idFailure = assertThrows(
                UpdaterException.class,
                () -> ReleaseJsonParser.parse(fractionalId, "plugin.jar", 1024));
        assertEquals(UpdateFailure.RELEASE_FORMAT_INVALID, idFailure.failure());
    }
}
