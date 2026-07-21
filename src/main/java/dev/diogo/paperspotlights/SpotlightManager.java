package dev.diogo.paperspotlights;

import dev.diogo.paperspotlights.controller.ControllerDial;
import dev.diogo.paperspotlights.light.LightFieldService;
import dev.diogo.paperspotlights.model.BlockPosition;
import dev.diogo.paperspotlights.model.Plane;
import dev.diogo.paperspotlights.model.Shape;
import dev.diogo.paperspotlights.model.Spotlight;
import dev.diogo.paperspotlights.persistence.SpotlightRepository;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.Serial;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Coordinates persistence, overlap resolution, and bounded world updates. */
public final class SpotlightManager {

    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,24}");
    private static final int ABSOLUTE_MAX_RADIUS = 32;

    private final JavaPlugin plugin;
    private final SpotlightRepository repository;
    private final LightFieldService lightField;
    private final NamespacedKey controllerKey;
    private final int maxRadius;
    private final int changesPerTick;

    private final Map<UUID, Spotlight> byId = new LinkedHashMap<>();
    private final Map<String, UUID> byName = new LinkedHashMap<>();
    private final Map<UUID, UUID> byController = new LinkedHashMap<>();
    private final LinkedHashSet<BlockPosition> pending = new LinkedHashSet<>();

    private BukkitTask worker;
    private boolean managedStateDirty;
    private int persistenceRetryDelayTicks;
    private int sweepDelayTicks = 100;

    public SpotlightManager(
            JavaPlugin plugin,
            SpotlightRepository repository,
            LightFieldService lightField,
            NamespacedKey controllerKey,
            int maxRadius,
            int changesPerTick
    ) {
        this.plugin = plugin;
        this.repository = repository;
        this.lightField = lightField;
        this.controllerKey = controllerKey;
        this.maxRadius = maxRadius;
        this.changesPerTick = changesPerTick;
    }

    public void initialize() throws IOException {
        SpotlightRepository.State state = repository.load();
        for (Spotlight spotlight : state.spotlights()) {
            if (!NAME_PATTERN.matcher(spotlight.name()).matches()) {
                throw new IOException("Spotlight state contains an invalid name: " + spotlight.name());
            }
            if (NamespacedKey.fromString(spotlight.origin().worldKey()) == null) {
                throw new IOException(
                        "Spotlight " + spotlight.name() + " contains an invalid world key"
                );
            }
            if (spotlight.radius() < 1 || spotlight.radius() > ABSOLUTE_MAX_RADIUS) {
                throw new IOException(
                        "Spotlight " + spotlight.name() + " has a radius outside 1-"
                                + ABSOLUTE_MAX_RADIUS
                );
            }
            if (spotlight.intensity() < 1) {
                throw new IOException(
                        "Spotlight " + spotlight.name() + " has an invalid zero intensity"
                );
            }
            if (spotlight.radius() > maxRadius) {
                plugin.getLogger().warning(
                        "Existing spotlight '" + spotlight.name() + "' exceeds max-radius; it was kept."
                );
            }
            byId.put(spotlight.id(), spotlight);
        }
        try {
            reindex();
        } catch (IllegalStateException exception) {
            throw new IOException("State contains duplicate spotlight names or controllers", exception);
        }

        lightField.loadManaged(state.managedLights());
        Set<BlockPosition> affected = lightField.rebuild(byId.values());
        Set<BlockPosition> claimsAdded = lightField.prepareClaims(affected);
        if (!claimsAdded.isEmpty()) {
            persist();
        }
        enqueue(affected);

        for (Spotlight spotlight : byId.values()) {
            tagLoadedController(spotlight);
        }
        worker = Bukkit.getScheduler().runTaskTimer(plugin, this::processBatch, 1L, 1L);
    }

    public Spotlight create(
            String name,
            BlockPosition origin,
            BlockPosition target,
            Plane plane,
            Shape shape,
            int radius,
            ItemFrame controller
    ) throws OperationException {
        validateName(name);
        if (radius < 1 || radius > maxRadius) {
            throw new OperationException("Radius must be between 1 and " + maxRadius + ".");
        }
        if (!origin.worldKey().equals(target.worldKey())) {
            throw new OperationException("The origin and target must be in the same world.");
        }
        validateBuildHeight(origin, "origin");
        validateBuildHeight(target, "area centre");
        if (!controller.getWorld().getKey().toString().equals(origin.worldKey())) {
            throw new OperationException("The controller must be in the same world as the spotlight.");
        }
        if (byName.containsKey(normalize(name))) {
            throw new OperationException("A spotlight named '" + name + "' already exists.");
        }
        if (byController.containsKey(controller.getUniqueId())) {
            throw new OperationException("That clock frame already controls another spotlight.");
        }
        if (controller.getItem().getType() != Material.CLOCK) {
            throw new OperationException("The selected item frame no longer contains a clock.");
        }

        Spotlight spotlight = new Spotlight(
                UUID.randomUUID(),
                name,
                origin,
                target,
                plane,
                shape,
                radius,
                15,
                true,
                controller.getUniqueId()
        );
        commitMutation(() -> byId.put(spotlight.id(), spotlight));

        controller.getPersistentDataContainer().set(
                controllerKey,
                PersistentDataType.STRING,
                spotlight.id().toString()
        );
        controller.setRotation(ControllerDial.rotationForIntensity(spotlight.intensity()));
        return spotlight;
    }

    public Spotlight toggle(String name) throws OperationException {
        Spotlight current = requiredByName(name);
        Spotlight replacement = current.withEnabled(!current.enabled());
        commitMutation(() -> byId.put(current.id(), replacement));
        return replacement;
    }

    public Spotlight setIntensity(String name, int intensity) throws OperationException {
        if (intensity < 1 || intensity > 15) {
            throw new OperationException("Intensity must be between 1 and 15; use toggle for OFF.");
        }
        Spotlight current = requiredByName(name);
        Spotlight replacement = current.withIntensity(intensity);
        commitMutation(() -> byId.put(current.id(), replacement));
        alignLoadedController(replacement);
        return replacement;
    }

    public Spotlight remove(String name) throws OperationException {
        Spotlight current = requiredByName(name);
        commitMutation(() -> byId.remove(current.id()));
        clearLoadedControllerTag(current);
        return current;
    }

    public Optional<Spotlight> findByName(String name) {
        UUID id = byName.get(normalize(name));
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    public Optional<Spotlight> findByController(UUID controllerUuid) {
        UUID id = byController.get(controllerUuid);
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    public List<Spotlight> all() {
        List<Spotlight> result = new ArrayList<>(byId.values());
        result.sort(Comparator.comparing(Spotlight::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(result);
    }

    public int maxRadius() {
        return maxRadius;
    }

    public boolean removeUnmanagedLight(BlockPosition position) {
        return lightField.removeUnmanagedLight(position);
    }

    public void syncLoadedController(ItemFrame frame) {
        Optional<Spotlight> spotlight = findByController(frame.getUniqueId());
        if (spotlight.isEmpty()) {
            frame.getPersistentDataContainer().remove(controllerKey);
            return;
        }
        frame.getPersistentDataContainer().set(
                controllerKey,
                PersistentDataType.STRING,
                spotlight.get().id().toString()
        );
        frame.setRotation(ControllerDial.rotationForIntensity(spotlight.get().intensity()));
    }

    public void reconcilePosition(BlockPosition position) {
        reconcilePositions(Set.of(position));
    }

    public void reconcilePositions(Collection<BlockPosition> positions) {
        reconcileLoaded(lightField.relevantPositions(positions));
    }

    public void reconcileChunk(String worldKey, int chunkX, int chunkZ) {
        Set<BlockPosition> positions = lightField.relevantPositions(worldKey, chunkX, chunkZ);
        reconcileLoaded(positions);
    }

    public void close() {
        if (worker != null) {
            worker.cancel();
            worker = null;
        }
        persistOrLog();
    }

    private void commitMutation(Runnable mutation) throws OperationException {
        Map<UUID, Spotlight> before = new LinkedHashMap<>(byId);
        Map<BlockPosition, String> managedBefore = lightField.managedSnapshot();
        mutation.run();
        try {
            reindex();
            Set<BlockPosition> affected = lightField.rebuild(byId.values());
            lightField.prepareClaims(affected);
            persist();
            enqueue(affected);
        } catch (IOException | IllegalStateException exception) {
            byId.clear();
            byId.putAll(before);
            reindex();
            lightField.loadManaged(managedBefore);
            lightField.rebuild(byId.values());
            throw new OperationException("Could not save the spotlight state; nothing was changed.", exception);
        }
    }

    private void reindex() {
        byName.clear();
        byController.clear();
        for (Spotlight spotlight : byId.values()) {
            UUID duplicateName = byName.put(normalize(spotlight.name()), spotlight.id());
            UUID duplicateController = byController.put(spotlight.controllerUuid(), spotlight.id());
            if (duplicateName != null || duplicateController != null) {
                throw new IllegalStateException("Duplicate spotlight index");
            }
        }
    }

    private void processBatch() {
        int processed = 0;
        Iterator<BlockPosition> iterator = pending.iterator();
        while (iterator.hasNext() && processed < changesPerTick) {
            BlockPosition position = iterator.next();
            iterator.remove();
            LightFieldService.ApplyResult result = lightField.applyOne(position);
            if (result == LightFieldService.ApplyResult.CLAIM_RELEASED) {
                managedStateDirty = true;
            }
            processed++;
        }

        if (persistenceRetryDelayTicks > 0) {
            persistenceRetryDelayTicks--;
        }
        if (pending.isEmpty() && managedStateDirty && persistenceRetryDelayTicks == 0) {
            try {
                persist();
                managedStateDirty = false;
            } catch (IOException exception) {
                plugin.getLogger().severe("Could not persist released light claims: " + exception.getMessage());
                persistenceRetryDelayTicks = 200;
            }
        }

        if (pending.isEmpty()) {
            if (sweepDelayTicks > 0) {
                sweepDelayTicks--;
            } else {
                reconcileLoaded(lightField.relevantLoadedPositions());
                sweepDelayTicks = 100;
            }
        }
    }

    private void enqueue(Collection<BlockPosition> positions) {
        pending.addAll(positions);
    }

    private void reconcileLoaded(Collection<BlockPosition> positions) {
        Set<BlockPosition> claimsAdded = lightField.prepareClaims(positions);
        if (!claimsAdded.isEmpty()) {
            try {
                persist();
            } catch (IOException exception) {
                lightField.rollbackClaims(claimsAdded);
                plugin.getLogger().severe(
                        "Could not save new light claims; those cells were left unchanged: "
                                + exception.getMessage()
                );
            }
        }
        enqueue(positions);
    }

    private void persist() throws IOException {
        repository.save(byId.values(), lightField.managedSnapshot());
    }

    private void persistOrLog() {
        try {
            persist();
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not save spotlight state: " + exception.getMessage());
        }
    }

    private Spotlight requiredByName(String name) throws OperationException {
        return findByName(name)
                .orElseThrow(() -> new OperationException("No spotlight named '" + name + "'."));
    }

    private static void validateName(String name) throws OperationException {
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new OperationException("Names use 1-24 letters, numbers, underscores, or hyphens.");
        }
    }

    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static void validateBuildHeight(BlockPosition position, String description)
            throws OperationException {
        NamespacedKey key = NamespacedKey.fromString(position.worldKey());
        World world = key == null ? null : Bukkit.getWorld(key);
        if (world == null) {
            throw new OperationException("The " + description + " world is not loaded.");
        }
        if (position.y() < world.getMinHeight() || position.y() >= world.getMaxHeight()) {
            throw new OperationException("The " + description + " is outside the world's build height.");
        }
    }

    private void tagLoadedController(Spotlight spotlight) {
        Entity entity = Bukkit.getEntity(spotlight.controllerUuid());
        if (entity instanceof ItemFrame frame) {
            frame.getPersistentDataContainer().set(
                    controllerKey,
                    PersistentDataType.STRING,
                    spotlight.id().toString()
            );
            alignLoadedController(spotlight);
        }
    }

    private static void alignLoadedController(Spotlight spotlight) {
        Entity entity = Bukkit.getEntity(spotlight.controllerUuid());
        if (entity instanceof ItemFrame frame) {
            frame.setRotation(ControllerDial.rotationForIntensity(spotlight.intensity()));
        }
    }

    private void clearLoadedControllerTag(Spotlight spotlight) {
        Entity entity = Bukkit.getEntity(spotlight.controllerUuid());
        if (entity instanceof ItemFrame frame) {
            frame.getPersistentDataContainer().remove(controllerKey);
        }
    }

    public static final class OperationException extends Exception {

        @Serial
        private static final long serialVersionUID = 1L;

        public OperationException(String message) {
            super(message);
        }

        public OperationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
