package dev.diogo.paperspotlights.update;

import java.util.Objects;
import java.util.OptionalLong;
import java.util.regex.Pattern;

/** The selected release and its exact configured asset. */
public record GitHubRelease(String tagName, SemVer version, Asset asset) {
    public GitHubRelease {
        Objects.requireNonNull(tagName, "tagName");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(asset, "asset");
        if (!tagName.equals(version.tag())) {
            throw new IllegalArgumentException("tagName and version must describe the same tag");
        }
    }

    /**
     * A GitHub release asset. The SHA-256 value is normalized to 64 lower-case
     * hexadecimal characters, without the {@code sha256:} prefix.
     */
    public record Asset(long id, String name, OptionalLong sizeBytes, String sha256Hex) {
        private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

        public Asset {
            if (id <= 0) {
                throw new IllegalArgumentException("Asset id must be positive");
            }
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(sizeBytes, "sizeBytes");
            Objects.requireNonNull(sha256Hex, "sha256Hex");
            if (name.isEmpty()) {
                throw new IllegalArgumentException("Asset name must not be empty");
            }
            if (sizeBytes.isPresent() && sizeBytes.getAsLong() < 0) {
                throw new IllegalArgumentException("Asset size must not be negative");
            }
            if (!SHA_256.matcher(sha256Hex).matches()) {
                throw new IllegalArgumentException("Invalid SHA-256 digest");
            }
        }
    }
}
