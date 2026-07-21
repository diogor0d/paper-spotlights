package dev.diogo.paperspotlights.ui;

import dev.diogo.paperspotlights.model.BlockPosition;
import dev.diogo.paperspotlights.model.Plane;
import dev.diogo.paperspotlights.model.Shape;
import dev.diogo.paperspotlights.model.Spotlight;
import dev.diogo.paperspotlights.model.SpotlightColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;

/** Short, player-only particle preview; it creates no persistent entities. */
public final class PreviewService {

    private static final int BEAM_STEPS = 32;
    private static final int OUTLINE_STEPS = 48;

    public void show(Player player, Spotlight spotlight) {
        World world = world(spotlight.origin().worldKey());
        if (world == null || !player.getWorld().equals(world)) {
            return;
        }

        Location origin = centered(world, spotlight.origin());
        Location target = centered(world, spotlight.target());
        for (int step = 0; step <= BEAM_STEPS; step++) {
            double amount = step / (double) BEAM_STEPS;
            Location point = origin.clone().add(
                    (target.getX() - origin.getX()) * amount,
                    (target.getY() - origin.getY()) * amount,
                    (target.getZ() - origin.getZ()) * amount
            );
            showParticle(player, point, spotlight.color());
        }

        if (spotlight.shape() == Shape.CIRCLE) {
            for (int step = 0; step < OUTLINE_STEPS; step++) {
                double angle = Math.TAU * step / OUTLINE_STEPS;
                showPlanePoint(player, target, spotlight.plane(), spotlight.color(),
                        Math.cos(angle) * spotlight.radius(),
                        Math.sin(angle) * spotlight.radius());
            }
        } else {
            int radius = spotlight.radius();
            for (int offset = -radius; offset <= radius; offset++) {
                showPlanePoint(player, target, spotlight.plane(), spotlight.color(), offset, -radius);
                showPlanePoint(player, target, spotlight.plane(), spotlight.color(), offset, radius);
                showPlanePoint(player, target, spotlight.plane(), spotlight.color(), -radius, offset);
                showPlanePoint(player, target, spotlight.plane(), spotlight.color(), radius, offset);
            }
        }
    }

    private static void showPlanePoint(
            Player player,
            Location center,
            Plane plane,
            SpotlightColor color,
            double u,
            double v
    ) {
        Location point = switch (plane) {
            case XZ -> center.clone().add(u, 0.0, v);
            case XY -> center.clone().add(u, v, 0.0);
            case ZY -> center.clone().add(0.0, v, u);
        };
        showParticle(player, point, color);
    }

    private static void showParticle(Player player, Location point, SpotlightColor color) {
        if (color.particleColor().isPresent()) {
            player.spawnParticle(
                    Particle.DUST,
                    point,
                    1,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    new Particle.DustOptions(color.particleColor().orElseThrow(), 0.7f)
            );
        } else {
            player.spawnParticle(Particle.END_ROD, point, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    private static Location centered(World world, BlockPosition position) {
        return new Location(world, position.x() + 0.5, position.y() + 0.5, position.z() + 0.5);
    }

    private static World world(String value) {
        NamespacedKey key = NamespacedKey.fromString(value);
        return key == null ? null : Bukkit.getWorld(key);
    }
}
