package dev.diogo.paperspotlights.update;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ReleaseJsonParser {
    private static final Pattern POSITIVE_LONG = Pattern.compile("[1-9][0-9]*");
    private static final Pattern NON_NEGATIVE_LONG = Pattern.compile("0|[1-9][0-9]*");
    private static final Pattern SHA_256 = Pattern.compile("sha256:([0-9A-Fa-f]{64})");

    private ReleaseJsonParser() {
    }

    static GitHubRelease parse(String json, String expectedAssetName, long maxDownloadBytes)
            throws UpdaterException {
        try {
            UpdateValidation.requireAssetName(expectedAssetName);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new UpdaterException(
                    UpdateFailure.RELEASE_FORMAT_INVALID,
                    "The configured asset name is invalid",
                    exception);
        }
        if (maxDownloadBytes <= 0) {
            throw new IllegalArgumentException("maxDownloadBytes must be positive");
        }

        Object parsed = MiniJson.parse(json);
        if (!(parsed instanceof Map<?, ?> releaseObject)) {
            throw invalid("GitHub release response must be a JSON object");
        }

        String tagName = requireString(releaseObject, "tag_name", "release tag");
        SemVer version;
        try {
            version = SemVer.parseTag(tagName);
        } catch (IllegalArgumentException exception) {
            throw new UpdaterException(
                    UpdateFailure.RELEASE_FORMAT_INVALID,
                    "GitHub release tag is not a supported Semantic Version",
                    exception);
        }

        Object assetsValue = releaseObject.get("assets");
        if (!(assetsValue instanceof List<?> assets)) {
            throw invalid("GitHub release assets must be a JSON array");
        }

        GitHubRelease.Asset selected = null;
        for (Object assetValue : assets) {
            if (!(assetValue instanceof Map<?, ?> assetObject)) {
                continue;
            }
            Object nameValue = assetObject.get("name");
            if (!(nameValue instanceof String name) || !name.equals(expectedAssetName)) {
                continue;
            }
            if (selected != null) {
                throw new UpdaterException(
                        UpdateFailure.ASSET_AMBIGUOUS,
                        "The release contains more than one asset with the configured name");
            }
            selected = parseSelectedAsset(assetObject, name, maxDownloadBytes);
        }

        if (selected == null) {
            throw new UpdaterException(
                    UpdateFailure.ASSET_NOT_FOUND,
                    "The release does not contain the configured asset");
        }
        return new GitHubRelease(tagName, version, selected);
    }

    private static GitHubRelease.Asset parseSelectedAsset(
            Map<?, ?> assetObject, String name, long maxDownloadBytes) throws UpdaterException {
        long id = parsePositiveLong(assetObject.get("id"), "asset id");

        Object digestValue = assetObject.get("digest");
        if (!(digestValue instanceof String digest)) {
            throw invalid("The selected asset has no SHA-256 digest");
        }
        Matcher digestMatcher = SHA_256.matcher(digest);
        if (!digestMatcher.matches()) {
            throw invalid("The selected asset digest must use sha256:<64 hex> form");
        }

        OptionalLong size = OptionalLong.empty();
        Object sizeValue = assetObject.get("size");
        if (sizeValue != null) {
            long sizeBytes = parseNonNegativeLong(sizeValue, "asset size");
            if (sizeBytes > maxDownloadBytes) {
                throw new UpdaterException(
                        UpdateFailure.DOWNLOAD_TOO_LARGE,
                        "The declared asset size exceeds the configured limit");
            }
            size = OptionalLong.of(sizeBytes);
        }

        return new GitHubRelease.Asset(
                id,
                name,
                size,
                digestMatcher.group(1).toLowerCase(Locale.ROOT));
    }

    private static String requireString(Map<?, ?> object, String key, String description)
            throws UpdaterException {
        Object value = object.get(key);
        if (!(value instanceof String stringValue)) {
            throw invalid("GitHub " + description + " must be a string");
        }
        return stringValue;
    }

    private static long parsePositiveLong(Object value, String description) throws UpdaterException {
        if (!(value instanceof MiniJson.JsonNumber number)
                || !POSITIVE_LONG.matcher(number.lexicalValue()).matches()) {
            throw invalid("GitHub " + description + " must be a positive integer");
        }
        return parseLong(number.lexicalValue(), description);
    }

    private static long parseNonNegativeLong(Object value, String description)
            throws UpdaterException {
        if (!(value instanceof MiniJson.JsonNumber number)
                || !NON_NEGATIVE_LONG.matcher(number.lexicalValue()).matches()) {
            throw invalid("GitHub " + description + " must be a non-negative integer");
        }
        return parseLong(number.lexicalValue(), description);
    }

    private static long parseLong(String value, String description) throws UpdaterException {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new UpdaterException(
                    UpdateFailure.RELEASE_FORMAT_INVALID,
                    "GitHub " + description + " exceeds the supported range",
                    exception);
        }
    }

    private static UpdaterException invalid(String message) {
        return new UpdaterException(UpdateFailure.RELEASE_FORMAT_INVALID, message);
    }
}
