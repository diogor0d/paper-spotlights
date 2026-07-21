package dev.diogo.paperspotlights.color;

import dev.diogo.paperspotlights.model.BlockPosition;
import dev.diogo.paperspotlights.model.NightSchedule;
import dev.diogo.paperspotlights.model.Plane;
import dev.diogo.paperspotlights.model.Shape;
import dev.diogo.paperspotlights.model.Spotlight;
import dev.diogo.paperspotlights.model.SpotlightColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Produces a deliberately subtle colored-particle wash for vanilla clients.
 *
 * <p>Minecraft's light engine has no RGB channel: the real illumination remains
 * the vanilla white {@code LIGHT} blocks managed by {@code LightFieldService}.
 * This service writes no blocks and creates no persistent entities. All Bukkit
 * access happens in its server-thread task. Each cycle is bounded per player
 * by the configured effect checks, particle packets, and particle count.</p>
 */
public final class ColoredLightEffectService {

    public static final int DEFAULT_INTERVAL_TICKS = 10;
    public static final double DEFAULT_VIEW_DISTANCE = 48.0;
    public static final int DEFAULT_PARTICLES_PER_PLAYER = 48;
    public static final int DEFAULT_EFFECT_CHECKS_PER_PLAYER = 64;
    public static final int DEFAULT_EFFECT_PACKETS_PER_PLAYER = 12;

    private static final double SURFACE_INSET = 0.42;

    private final JavaPlugin plugin;
    private final int intervalTicks;
    private final double viewDistanceSquared;
    private final int particleBudget;
    private final int effectCheckBudget;
    private final int packetBudget;

    private List<Effect> effects = List.of();
    private BukkitTask worker;
    private int firstEffect;

    public ColoredLightEffectService(JavaPlugin plugin) {
        this(
                plugin,
                DEFAULT_INTERVAL_TICKS,
                DEFAULT_VIEW_DISTANCE,
                DEFAULT_PARTICLES_PER_PLAYER,
                DEFAULT_EFFECT_CHECKS_PER_PLAYER,
                DEFAULT_EFFECT_PACKETS_PER_PLAYER
        );
    }

    public ColoredLightEffectService(
            JavaPlugin plugin,
            int intervalTicks,
            double viewDistance,
            int particleBudget,
            int effectCheckBudget,
            int packetBudget
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        if (intervalTicks < 1) {
            throw new IllegalArgumentException("intervalTicks must be positive");
        }
        if (!Double.isFinite(viewDistance) || viewDistance <= 0.0) {
            throw new IllegalArgumentException("viewDistance must be finite and positive");
        }
        if (particleBudget < 1 || effectCheckBudget < 1 || packetBudget < 1) {
            throw new IllegalArgumentException("effect budgets must be positive");
        }
        this.intervalTicks = intervalTicks;
        this.viewDistanceSquared = viewDistance * viewDistance;
        this.particleBudget = particleBudget;
        this.effectCheckBudget = effectCheckBudget;
        this.packetBudget = packetBudget;
    }

    /** Replaces the immutable rendering snapshot; call on the server thread. */
    public void replaceEffects(Collection<Effect> replacement) {
        effects = List.copyOf(replacement);
        if (effects.isEmpty()) {
            firstEffect = 0;
        } else {
            firstEffect %= effects.size();
        }
    }

    /** Starts the bounded server-thread renderer. Calling this twice is harmless. */
    public void start() {
        if (worker == null) {
            worker = plugin.getServer().getScheduler().runTaskTimer(
                    plugin,
                    this::renderCycle,
                    intervalTicks,
                    intervalTicks
            );
        }
    }

    public void close() {
        if (worker != null) {
            worker.cancel();
            worker = null;
        }
        effects = List.of();
        firstEffect = 0;
    }

    private void renderCycle() {
        List<Effect> snapshot = effects;
        if (snapshot.isEmpty()) {
            return;
        }

        int start = firstEffect % snapshot.size();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            renderFor(player, snapshot, start);
        }
        firstEffect = (start + effectCheckBudget) % snapshot.size();
    }

    private void renderFor(Player player, List<Effect> snapshot, int start) {
        int particlesLeft = particleBudget;
        int packetsLeft = packetBudget;
        int checks = Math.min(effectCheckBudget, snapshot.size());
        String playerWorld = player.getWorld().getKey().toString();

        for (int offset = 0; offset < checks && particlesLeft > 0 && packetsLeft > 0; offset++) {
            Effect effect = snapshot.get((start + offset) % snapshot.size());
            if (!effect.enabled()
                    || effect.nightOnly() && !NightSchedule.isNight(player.getWorld().getTime())
                    || effect.color() == SpotlightColor.NONE
                    || !effect.target().worldKey().equals(playerWorld)) {
                continue;
            }

            Location centre = surfaceCentre(player.getWorld(), effect);
            if (player.getLocation().distanceSquared(centre) > viewDistanceSquared) {
                continue;
            }

            int count = Math.min(particlesLeft, particleCount(effect.radius(), effect.intensity()));
            double spread = Math.max(0.35, effect.radius() * spreadFactor(effect.shape()));
            double normalSpread = 0.025;
            double spreadX = effect.plane() == Plane.ZY ? normalSpread : spread;
            double spreadY = effect.plane() == Plane.XZ ? normalSpread : spread;
            double spreadZ = effect.plane() == Plane.XY ? normalSpread : spread;
            float size = 0.35f + (0.45f * effect.intensity() / 15.0f);

            player.spawnParticle(
                    Particle.DUST,
                    centre,
                    count,
                    spreadX,
                    spreadY,
                    spreadZ,
                    0.0,
                    new Particle.DustOptions(effect.color().particleColor().orElseThrow(), size)
            );
            particlesLeft -= count;
            packetsLeft--;
        }
    }

    static int particleCount(int radius, int intensity) {
        int fullBrightness = Math.clamp(radius + 3, 4, 12);
        double brightness = 0.25 + (0.75 * intensity / 15.0);
        return Math.max(1, (int) Math.ceil(fullBrightness * brightness));
    }

    private static double spreadFactor(Shape shape) {
        // Bukkit's particle offsets are Gaussian deviations. Keep roughly
        // three deviations within the actual footprint instead of treating
        // the offsets as hard extents.
        return shape == Shape.CIRCLE ? 0.28 : 0.33;
    }

    private static Location surfaceCentre(World world, Effect effect) {
        BlockPosition target = effect.target();
        BlockPosition origin = effect.origin();
        double x = target.x() + 0.5;
        double y = target.y() + 0.5;
        double z = target.z() + 0.5;

        // The selected target cell is adjacent to the clicked surface. Its
        // origin-facing side normally points away from that surface.
        switch (effect.plane()) {
            case XZ -> y -= Math.signum(origin.y() - target.y()) * SURFACE_INSET;
            case XY -> z -= Math.signum(origin.z() - target.z()) * SURFACE_INSET;
            case ZY -> x -= Math.signum(origin.x() - target.x()) * SURFACE_INSET;
        }
        return new Location(world, x, y, z);
    }

    /** Immutable bridge between persisted spotlight state and the renderer. */
    public record Effect(
            UUID spotlightId,
            BlockPosition origin,
            BlockPosition target,
            Plane plane,
            Shape shape,
            int radius,
            int intensity,
            boolean enabled,
            boolean nightOnly,
            SpotlightColor color
    ) {

        public Effect {
            Objects.requireNonNull(spotlightId, "spotlightId");
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(plane, "plane");
            Objects.requireNonNull(shape, "shape");
            Objects.requireNonNull(color, "color");
            if (!origin.worldKey().equals(target.worldKey())) {
                throw new IllegalArgumentException("effect origin and target worlds must match");
            }
            if (radius < 1 || radius > 32) {
                throw new IllegalArgumentException("effect radius must be 1-32");
            }
            if (intensity < 1 || intensity > 15) {
                throw new IllegalArgumentException("effect intensity must be 1-15");
            }
        }

        public Effect(
                UUID spotlightId,
                BlockPosition origin,
                BlockPosition target,
                Plane plane,
                Shape shape,
                int radius,
                int intensity,
                boolean enabled,
                SpotlightColor color
        ) {
            this(
                    spotlightId,
                    origin,
                    target,
                    plane,
                    shape,
                    radius,
                    intensity,
                    enabled,
                    false,
                    color
            );
        }

        public static Effect from(Spotlight spotlight) {
            Objects.requireNonNull(spotlight, "spotlight");
            return new Effect(
                    spotlight.id(),
                    spotlight.origin(),
                    spotlight.target(),
                    spotlight.plane(),
                    spotlight.shape(),
                    spotlight.radius(),
                    spotlight.intensity(),
                    spotlight.enabled(),
                    spotlight.nightOnly(),
                    spotlight.color()
            );
        }
    }
}
