package dev.diogo.paperspotlights.update;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

final class UpdateValidation {
    private static final Pattern OWNER = Pattern.compile(
            "[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?");
    private static final Pattern REPOSITORY = Pattern.compile(
            "[A-Za-z0-9](?:[A-Za-z0-9._-]{0,98}[A-Za-z0-9])?");
    private static final Pattern ASSET_NAME = Pattern.compile(
            "[A-Za-z0-9](?:[A-Za-z0-9._-]{0,253}[A-Za-z0-9_-])?");
    private static final Set<String> WINDOWS_RESERVED_STEMS = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private UpdateValidation() {
    }

    static String requireOwner(String owner) {
        Objects.requireNonNull(owner, "owner");
        if (!OWNER.matcher(owner).matches() || owner.contains("--")) {
            throw new IllegalArgumentException("Invalid GitHub repository owner");
        }
        return owner;
    }

    static String requireRepository(String repository) {
        Objects.requireNonNull(repository, "repository");
        if (!REPOSITORY.matcher(repository).matches() || repository.equals(".") || repository.equals("..")) {
            throw new IllegalArgumentException("Invalid GitHub repository name");
        }
        return repository;
    }

    static String requireAssetName(String assetName) {
        Objects.requireNonNull(assetName, "assetName");
        if (!ASSET_NAME.matcher(assetName).matches() || assetName.contains("..")) {
            throw new IllegalArgumentException("Asset name must be a safe file name");
        }
        String stem = assetName.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
        if (WINDOWS_RESERVED_STEMS.contains(stem)) {
            throw new IllegalArgumentException("Asset name is reserved on Windows");
        }
        return assetName;
    }

    static Duration requirePositiveDuration(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }

    static String requireDescriptorValue(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > 255 || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must be a short, nonblank descriptor value");
        }
        return value;
    }
}
