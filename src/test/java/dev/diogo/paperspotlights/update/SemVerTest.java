package dev.diogo.paperspotlights.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SemVerTest {
    @Test
    void followsSemanticVersionPrereleasePrecedence() {
        List<SemVer> versions = List.of(
                SemVer.parseTag("v1.0.0-alpha"),
                SemVer.parseTag("v1.0.0-alpha.1"),
                SemVer.parseTag("v1.0.0-alpha.beta"),
                SemVer.parseTag("v1.0.0-beta"),
                SemVer.parseTag("v1.0.0-beta.2"),
                SemVer.parseTag("v1.0.0-beta.11"),
                SemVer.parseTag("v1.0.0-rc.1"),
                SemVer.parseTag("v1.0.0"));

        for (int index = 1; index < versions.size(); index++) {
            assertTrue(versions.get(index - 1).compareTo(versions.get(index)) < 0);
        }
    }

    @Test
    void ignoresBuildMetadataForPrecedenceAndSupportsLargeComponents() {
        SemVer firstBuild = SemVer.parseTag("v999999999999999999999.2.3+build.1");
        SemVer secondBuild = SemVer.parseTag("v999999999999999999999.2.3+build.2");

        assertEquals(0, firstBuild.compareTo(secondBuild));
        assertTrue(firstBuild.compareTo(SemVer.parseTag("v999999999999999999998.99.99")) > 0);
    }

    @Test
    void rejectsNonCanonicalTags() {
        List<String> invalidTags = List.of(
                "1.2.3",
                "v1.2",
                "v01.2.3",
                "v1.02.3",
                "v1.2.03",
                "v1.2.3-01",
                "v1.2.3-",
                "v1.2.3-alpha_1");

        for (String invalidTag : invalidTags) {
            assertThrows(IllegalArgumentException.class, () -> SemVer.parseTag(invalidTag));
        }
    }
}
