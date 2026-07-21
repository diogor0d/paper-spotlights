package dev.diogo.paperspotlights.update;

import java.time.Duration;

/** Immutable configuration for the public GitHub Releases updater. */
public record UpdaterConfig(
        String owner,
        String repository,
        String assetName,
        long maxDownloadBytes,
        String expectedPluginName,
        String expectedMainClass,
        String expectedApiVersion,
        Duration connectTimeout,
        Duration requestTimeout) {
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    public UpdaterConfig {
        owner = UpdateValidation.requireOwner(owner);
        repository = UpdateValidation.requireRepository(repository);
        assetName = UpdateValidation.requireAssetName(assetName);
        if (maxDownloadBytes <= 0) {
            throw new IllegalArgumentException("maxDownloadBytes must be positive");
        }
        expectedPluginName = UpdateValidation.requireDescriptorValue(
                expectedPluginName, "expectedPluginName");
        expectedMainClass = UpdateValidation.requireDescriptorValue(
                expectedMainClass, "expectedMainClass");
        expectedApiVersion = UpdateValidation.requireDescriptorValue(
                expectedApiVersion, "expectedApiVersion");
        connectTimeout = UpdateValidation.requirePositiveDuration(connectTimeout, "connectTimeout");
        requestTimeout = UpdateValidation.requirePositiveDuration(requestTimeout, "requestTimeout");
    }

    public UpdaterConfig(
            String owner,
            String repository,
            String assetName,
            long maxDownloadBytes,
            String expectedPluginName,
            String expectedMainClass,
            String expectedApiVersion
    ) {
        this(
                owner,
                repository,
                assetName,
                maxDownloadBytes,
                expectedPluginName,
                expectedMainClass,
                expectedApiVersion,
                DEFAULT_CONNECT_TIMEOUT,
                DEFAULT_REQUEST_TIMEOUT);
    }
}
