package dev.diogo.paperspotlights.persistence;

import dev.diogo.paperspotlights.model.BlockPosition;
import dev.diogo.paperspotlights.model.Plane;
import dev.diogo.paperspotlights.model.Shape;
import dev.diogo.paperspotlights.model.Spotlight;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Versioned YAML storage with a previous-state backup and atomic replacement
 * where supported. The state file is authoritative; LIGHT blocks themselves
 * cannot carry ownership metadata.
 */
public final class SpotlightRepository {

    private static final int SCHEMA_VERSION = 1;

    private final Path statePath;
    private final Path temporaryPath;
    private final Path backupPath;

    public SpotlightRepository(Path dataDirectory) {
        this.statePath = dataDirectory.resolve("state.yml");
        this.temporaryPath = dataDirectory.resolve("state.yml.tmp");
        this.backupPath = dataDirectory.resolve("state.yml.bak");
    }

    public State load() throws IOException {
        if (Files.notExists(statePath)) {
            return new State(List.of(), Map.of());
        }

        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(statePath.toFile());
        } catch (InvalidConfigurationException exception) {
            throw new IOException("Invalid YAML in " + statePath, exception);
        }

        int schemaVersion = yaml.getInt("schema-version", -1);
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IOException(
                    "Unsupported state schema " + schemaVersion + " (expected " + SCHEMA_VERSION + ")"
            );
        }

        List<Spotlight> spotlights = readSpotlights(yaml.getConfigurationSection("spotlights"));
        Map<BlockPosition, String> managedLights = readManagedLights(
                yaml.getConfigurationSection("managed-lights")
        );
        return new State(spotlights, managedLights);
    }

    public void save(Collection<Spotlight> spotlights, Map<BlockPosition, String> managedLights)
            throws IOException {
        Files.createDirectories(statePath.getParent());

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of(
                "PaperSpotlights runtime state. Stop the server before editing this file."
        ));
        yaml.set("schema-version", SCHEMA_VERSION);
        writeSpotlights(yaml.createSection("spotlights"), spotlights);
        writeManagedLights(yaml.createSection("managed-lights"), managedLights);

        Files.writeString(
                temporaryPath,
                yaml.saveToString(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        );

        if (Files.exists(statePath)) {
            Files.copy(statePath, backupPath, StandardCopyOption.REPLACE_EXISTING);
        }

        try {
            Files.move(
                    temporaryPath,
                    statePath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporaryPath, statePath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static List<Spotlight> readSpotlights(ConfigurationSection root) throws IOException {
        if (root == null) {
            return List.of();
        }

        List<Spotlight> result = new ArrayList<>();
        for (String idText : root.getKeys(false)) {
            ConfigurationSection section = requiredSection(root, idText);
            try {
                UUID id = UUID.fromString(idText);
                String name = requiredString(section, "name");
                String worldKey = requiredString(section, "world");
                BlockPosition origin = readPosition(section, "origin", worldKey);
                BlockPosition target = readPosition(section, "target", worldKey);
                Plane plane = Plane.valueOf(requiredString(section, "plane"));
                Shape shape = Shape.valueOf(requiredString(section, "shape"));
                int radius = section.getInt("radius", -1);
                int intensity = section.getInt("intensity", -1);
                boolean enabled = section.getBoolean("enabled", true);
                UUID controllerUuid = UUID.fromString(requiredString(section, "controller-uuid"));

                result.add(new Spotlight(
                        id,
                        name,
                        origin,
                        target,
                        plane,
                        shape,
                        radius,
                        intensity,
                        enabled,
                        controllerUuid
                ));
            } catch (IllegalArgumentException exception) {
                throw new IOException("Invalid spotlight entry " + idText, exception);
            }
        }
        return List.copyOf(result);
    }

    private static Map<BlockPosition, String> readManagedLights(ConfigurationSection root)
            throws IOException {
        if (root == null) {
            return Map.of();
        }

        Map<BlockPosition, String> result = new LinkedHashMap<>();
        for (String index : root.getKeys(false)) {
            ConfigurationSection section = requiredSection(root, index);
            String worldKey = requiredString(section, "world");
            BlockPosition position = readPosition(section, null, worldKey);
            String baseline = requiredString(section, "baseline");
            if (result.put(position, baseline) != null) {
                throw new IOException("Duplicate managed light at " + position);
            }
        }
        return Map.copyOf(result);
    }

    private static void writeSpotlights(ConfigurationSection root, Collection<Spotlight> spotlights) {
        spotlights.stream()
                .sorted(Comparator.comparing(Spotlight::name, String.CASE_INSENSITIVE_ORDER))
                .forEach(spotlight -> {
                    ConfigurationSection section = root.createSection(spotlight.id().toString());
                    section.set("name", spotlight.name());
                    section.set("world", spotlight.origin().worldKey());
                    writePosition(section.createSection("origin"), spotlight.origin());
                    writePosition(section.createSection("target"), spotlight.target());
                    section.set("plane", spotlight.plane().name());
                    section.set("shape", spotlight.shape().name());
                    section.set("radius", spotlight.radius());
                    section.set("intensity", spotlight.intensity());
                    section.set("enabled", spotlight.enabled());
                    section.set("controller-uuid", spotlight.controllerUuid().toString());
                });
    }

    private static void writeManagedLights(
            ConfigurationSection root,
            Map<BlockPosition, String> managedLights
    ) {
        List<Map.Entry<BlockPosition, String>> entries = new ArrayList<>(managedLights.entrySet());
        entries.sort(Map.Entry.comparingByKey(
                Comparator.comparing(BlockPosition::worldKey)
                        .thenComparingInt(BlockPosition::x)
                        .thenComparingInt(BlockPosition::y)
                        .thenComparingInt(BlockPosition::z)
        ));

        for (int index = 0; index < entries.size(); index++) {
            Map.Entry<BlockPosition, String> entry = entries.get(index);
            ConfigurationSection section = root.createSection(Integer.toString(index));
            section.set("world", entry.getKey().worldKey());
            writePosition(section, entry.getKey());
            section.set("baseline", entry.getValue());
        }
    }

    private static BlockPosition readPosition(
            ConfigurationSection parent,
            String child,
            String worldKey
    ) throws IOException {
        ConfigurationSection section = child == null ? parent : requiredSection(parent, child);
        if (!section.isInt("x") || !section.isInt("y") || !section.isInt("z")) {
            throw new IOException("Position is missing integer x/y/z fields at " + section.getCurrentPath());
        }
        return new BlockPosition(
                worldKey,
                section.getInt("x"),
                section.getInt("y"),
                section.getInt("z")
        );
    }

    private static void writePosition(ConfigurationSection section, BlockPosition position) {
        section.set("x", position.x());
        section.set("y", position.y());
        section.set("z", position.z());
    }

    private static ConfigurationSection requiredSection(ConfigurationSection parent, String path)
            throws IOException {
        ConfigurationSection section = parent.getConfigurationSection(path);
        if (section == null) {
            throw new IOException("Missing section " + parent.getCurrentPath() + "." + path);
        }
        return section;
    }

    private static String requiredString(ConfigurationSection section, String path) throws IOException {
        String value = section.getString(path);
        if (value == null || value.isBlank()) {
            throw new IOException("Missing value " + section.getCurrentPath() + "." + path);
        }
        return value;
    }

    public record State(List<Spotlight> spotlights, Map<BlockPosition, String> managedLights) {

        public State {
            spotlights = List.copyOf(spotlights);
            managedLights = Map.copyOf(managedLights);
        }
    }
}
