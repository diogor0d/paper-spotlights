package dev.diogo.paperspotlights;

import dev.diogo.paperspotlights.light.LightFieldService;
import dev.diogo.paperspotlights.persistence.SpotlightRepository;
import dev.diogo.paperspotlights.setup.LightingLens;
import dev.diogo.paperspotlights.setup.SetupSessions;
import dev.diogo.paperspotlights.ui.PreviewService;
import dev.diogo.paperspotlights.update.GitHubReleaseUpdater;
import dev.diogo.paperspotlights.update.SemVer;
import dev.diogo.paperspotlights.update.UpdateResult;
import dev.diogo.paperspotlights.update.UpdaterConfig;
import org.bukkit.NamespacedKey;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.CompletableFuture;

public final class PaperSpotlightsPlugin extends JavaPlugin {

    private SpotlightManager manager;
    private GitHubReleaseUpdater updater;
    private volatile CompletableFuture<UpdateResult> updateFuture;
    private boolean started;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        int configuredRadius = getConfig().getInt("max-radius", 16);
        int maxRadius = Math.clamp(configuredRadius, 1, 32);
        if (maxRadius != configuredRadius) {
            getLogger().warning("max-radius must be 1-32; using " + maxRadius + ".");
        }
        int configuredBatch = getConfig().getInt("changes-per-tick", 128);
        int changesPerTick = Math.clamp(configuredBatch, 16, 2048);
        if (changesPerTick != configuredBatch) {
            getLogger().warning("changes-per-tick must be 16-2048; using " + changesPerTick + ".");
        }

        NamespacedKey lensKey = new NamespacedKey(this, "gaffers-lens");
        NamespacedKey controllerKey = new NamespacedKey(this, "spotlight-id");
        LightingLens lens = new LightingLens(lensKey);
        SetupSessions sessions = new SetupSessions();
        PreviewService preview = new PreviewService();

        SpotlightRepository repository = new SpotlightRepository(getDataFolder().toPath());
        manager = new SpotlightManager(
                this,
                repository,
                new LightFieldService(),
                controllerKey,
                maxRadius,
                changesPerTick
        );

        try {
            manager.initialize();
        } catch (Exception exception) {
            getLogger().severe("PaperSpotlights did not start because its saved state could not be loaded safely.");
            getLogger().severe(exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        SpotlightCommand commandHandler = new SpotlightCommand(manager, sessions, lens, preview);
        PluginCommand command = getCommand("spotlight");
        if (command == null) {
            getLogger().severe("The spotlight command is missing from plugin.yml.");
            manager.close();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        command.setExecutor(commandHandler);
        command.setTabCompleter(commandHandler);
        getServer().getPluginManager().registerEvents(
                new SpotlightListener(this, manager, sessions, lens, controllerKey),
                this
        );

        started = true;
        getLogger().info("Enabled " + manager.all().size() + " spotlight(s). No NMS or version-specific internals in use.");
        startUpdater();
    }

    @Override
    public void onDisable() {
        CompletableFuture<UpdateResult> activeUpdate = updateFuture;
        updateFuture = null;
        if (activeUpdate != null) {
            activeUpdate.cancel(true);
        }
        if (updater != null) {
            updater.close();
            updater = null;
        }
        if (started && manager != null) {
            manager.close();
            started = false;
        }
    }

    private void startUpdater() {
        if (!getConfig().getBoolean("updates.enabled", true)) {
            getLogger().info("Automatic update checks are disabled.");
            return;
        }

        String configuredRepository = getConfig().getString("updates.repository", "").trim();
        if (configuredRepository.isEmpty()) {
            getLogger().info("Automatic update checks are inactive: set updates.repository in config.yml.");
            return;
        }
        String[] repositoryParts = configuredRepository.split("/", -1);
        if (repositoryParts.length != 2) {
            getLogger().warning("updates.repository must use the OWNER/REPOSITORY form; updater disabled.");
            return;
        }

        String assetName = getConfig().getString("updates.asset-name", "PaperSpotlights.jar").trim();
        int configuredSize = getConfig().getInt("updates.max-size-mib", 16);
        int maxSizeMib = Math.clamp(configuredSize, 1, 64);
        if (maxSizeMib != configuredSize) {
            getLogger().warning("updates.max-size-mib must be 1-64; using " + maxSizeMib + ".");
        }

        SemVer currentVersion;
        UpdaterConfig updaterConfig;
        try {
            currentVersion = SemVer.parseTag("v" + getPluginMeta().getVersion());
            updaterConfig = new UpdaterConfig(
                    repositoryParts[0],
                    repositoryParts[1],
                    assetName,
                    maxSizeMib * 1024L * 1024L,
                    getPluginMeta().getName(),
                    getPluginMeta().getMainClass(),
                    getPluginMeta().getAPIVersion()
            );
        } catch (IllegalArgumentException exception) {
            getLogger().warning("Invalid updater configuration: " + exception.getMessage());
            return;
        }

        updater = new GitHubReleaseUpdater(updaterConfig);
        getLogger().info("Checking GitHub Releases for PaperSpotlights updates asynchronously.");
        CompletableFuture<UpdateResult> check = updater.checkAndStageAsync(
                currentVersion,
                getServer().getUpdateFolderFile().toPath()
        );
        updateFuture = check;
        check.thenAccept(result -> {
            if (updateFuture == check && isEnabled()) {
                logUpdateResult(result);
            }
        });
    }

    private void logUpdateResult(UpdateResult result) {
        switch (result) {
            case UpdateResult.UpToDate upToDate -> getLogger().info(
                    "PaperSpotlights is up to date (" + upToDate.currentVersion().tag() + ")."
            );
            case UpdateResult.Staged staged -> getLogger().warning(
                    "Verified PaperSpotlights " + staged.release().version().tag()
                            + " and staged it for the next server restart."
            );
            case UpdateResult.Failed failed -> getLogger().warning(
                    "Automatic update check failed [" + failed.failure() + "]: " + failed.message()
            );
        }
    }
}
