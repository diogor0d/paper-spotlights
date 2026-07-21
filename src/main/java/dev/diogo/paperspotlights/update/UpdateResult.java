package dev.diogo.paperspotlights.update;

import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

/** Typed outcome from an update check and optional staging operation. */
public sealed interface UpdateResult
        permits UpdateResult.UpToDate, UpdateResult.Staged, UpdateResult.Failed {
    record UpToDate(SemVer currentVersion, GitHubRelease latestRelease) implements UpdateResult {
        public UpToDate {
            Objects.requireNonNull(currentVersion, "currentVersion");
            Objects.requireNonNull(latestRelease, "latestRelease");
        }
    }

    /** A verified asset atomically placed in Paper's update directory for the next restart. */
    record Staged(
            SemVer previousVersion,
            GitHubRelease release,
            Path target,
            long bytes,
            String sha256Hex) implements UpdateResult {
        private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

        public Staged {
            Objects.requireNonNull(previousVersion, "previousVersion");
            Objects.requireNonNull(release, "release");
            target = Objects.requireNonNull(target, "target").toAbsolutePath().normalize();
            Objects.requireNonNull(sha256Hex, "sha256Hex");
            if (bytes < 0) {
                throw new IllegalArgumentException("bytes must not be negative");
            }
            if (!SHA_256.matcher(sha256Hex).matches()) {
                throw new IllegalArgumentException("Invalid SHA-256 digest");
            }
        }
    }

    record Failed(UpdateFailure failure, String message) implements UpdateResult {
        public Failed {
            Objects.requireNonNull(failure, "failure");
            Objects.requireNonNull(message, "message");
            if (message.isBlank()) {
                throw new IllegalArgumentException("message must not be blank");
            }
        }
    }
}
