package dev.diogo.paperspotlights;

import dev.diogo.paperspotlights.controller.ControllerDial;
import dev.diogo.paperspotlights.model.BlockPosition;
import dev.diogo.paperspotlights.model.Plane;
import dev.diogo.paperspotlights.model.Spotlight;
import dev.diogo.paperspotlights.model.SpotlightColor;
import dev.diogo.paperspotlights.setup.LightingLens;
import dev.diogo.paperspotlights.setup.SetupSession;
import dev.diogo.paperspotlights.setup.SetupSessions;
import dev.diogo.paperspotlights.ui.Messages;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.FluidLevelChangeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Player setup, dimmer interaction, and world-reconciliation events. */
public final class SpotlightListener implements Listener {

    private final JavaPlugin plugin;
    private final SpotlightManager manager;
    private final SetupSessions sessions;
    private final LightingLens lens;
    private final NamespacedKey controllerKey;
    private final EventReconciliationCoalescer<BlockPosition> eventReconciler;

    public SpotlightListener(
            JavaPlugin plugin,
            SpotlightManager manager,
            SetupSessions sessions,
            LightingLens lens,
            NamespacedKey controllerKey
    ) {
        this.plugin = plugin;
        this.manager = manager;
        this.sessions = sessions;
        this.lens = lens;
        this.controllerKey = controllerKey;
        this.eventReconciler = new EventReconciliationCoalescer<>(
                manager::filterRelevantPositions,
                task -> plugin.getServer().getScheduler().runTask(plugin, task),
                manager::reconcilePositions
        );
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLensBlockClick(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || !lens.isLens(event.getItem())
                || event.getClickedBlock() == null) {
            return;
        }
        if (event.getAction() != Action.LEFT_CLICK_BLOCK
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);

        Player player = event.getPlayer();
        if (event.getAction() == Action.LEFT_CLICK_BLOCK
                && player.isSneaking()
                && event.getClickedBlock().getType() == Material.LIGHT) {
            BlockPosition clicked = toPosition(event.getClickedBlock());
            if (manager.removeUnmanagedLight(clicked)) {
                Messages.success(player, "Removed that unowned LIGHT block.");
            } else {
                Messages.error(player, "That LIGHT block belongs to a spotlight; remove its spotlight instead.");
            }
            return;
        }

        Block selected = event.getClickedBlock().getRelative(event.getBlockFace());
        if (selected.getY() < selected.getWorld().getMinHeight()
                || selected.getY() >= selected.getWorld().getMaxHeight()) {
            Messages.error(player, "That selection is outside the world's build height.");
            return;
        }
        BlockPosition position = toPosition(selected);
        SetupSession session = sessions.getOrCreate(player.getUniqueId());
        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            session.origin(position);
            Messages.success(player, "Beam origin selected at " + coordinates(position) + ".");
        } else {
            Plane plane = planeFor(event.getBlockFace());
            session.target(position, plane);
            Messages.success(
                    player,
                    "Area centre selected at " + coordinates(position) + " on the " + plane + " plane."
            );
        }
        showMarker(player, selected);
        promptIfReady(player, session);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemFrameClick(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof ItemFrame frame)) {
            return;
        }

        Optional<Spotlight> controlled = manager.findByController(frame.getUniqueId());
        if (controlled.isPresent()) {
            event.setCancelled(true);
            if (event.getHand() == EquipmentSlot.HAND) {
                useController(event.getPlayer(), controlled.get());
            }
            return;
        }

        Player player = event.getPlayer();
        boolean setupClick = lens.isLens(player.getInventory().getItemInMainHand());
        if (!setupClick) {
            return;
        }
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        // Remove harmless stale metadata left by a definition that was deleted
        // while this entity's chunk was unloaded.
        frame.getPersistentDataContainer().remove(controllerKey);
        if (frame.getItem().getType() != Material.CLOCK) {
            Messages.error(player, "Put a clock in that item frame first; it becomes the dimmer dial.");
            return;
        }

        SetupSession session = sessions.getOrCreate(player.getUniqueId());
        session.controller(frame.getUniqueId(), frame.getWorld().getKey().toString());
        Messages.success(player, "Clock-frame dimmer selected.");
        promptIfReady(player, session);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onControllerBreak(HangingBreakEvent event) {
        Optional<Spotlight> controlled = manager.findByController(event.getEntity().getUniqueId());
        if (controlled.isEmpty()) {
            return;
        }

        try {
            Spotlight removed = manager.remove(controlled.get().name());
            if (event instanceof HangingBreakByEntityEvent byEntity
                    && byEntity.getRemover() instanceof Player player) {
                Messages.success(player, "Removed spotlight '" + removed.name() + "' with its controller.");
            }
        } catch (SpotlightManager.OperationException exception) {
            event.setCancelled(true);
            Entity entity = event instanceof HangingBreakByEntityEvent byEntity
                    ? byEntity.getRemover()
                    : null;
            if (entity instanceof Player player) {
                Messages.error(player, exception.getMessage());
            }
            plugin.getLogger().severe("Could not remove broken spotlight controller: " + exception.getMessage());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        reconcileLater(List.of(toPosition(event.getBlock())));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (event instanceof BlockMultiPlaceEvent multiPlace) {
            reconcileLater(multiPlace.getReplacedBlockStates().stream()
                    .map(state -> toPosition(state.getBlock()))
                    .toList());
        } else {
            reconcileLater(List.of(toPosition(event.getBlockPlaced())));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFluidFlow(BlockFromToEvent event) {
        reconcileLater(List.of(toPosition(event.getBlock()), toPosition(event.getToBlock())));
    }

    @EventHandler(ignoreCancelled = true)
    public void onFluidLevelChange(FluidLevelChangeEvent event) {
        reconcileLater(List.of(toPosition(event.getBlock())));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        reconcileLater(List.of(toPosition(
                event.getBlockClicked().getRelative(event.getBlockFace())
        )));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent event) {
        reconcileLater(event.blockList().stream().map(SpotlightListener::toPosition).toList());
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplosion(EntityExplodeEvent event) {
        reconcileLater(event.blockList().stream().map(SpotlightListener::toPosition).toList());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        reconcilePiston(event.getBlocks(), event.getDirection());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        reconcilePiston(event.getBlocks(), event.getDirection());
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        manager.reconcileChunk(
                event.getWorld().getKey().toString(),
                event.getChunk().getX(),
                event.getChunk().getZ()
        );
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof ItemFrame frame) {
                manager.syncLoadedController(frame);
            }
        }
    }

    private void useController(Player player, Spotlight current) {
        try {
            Spotlight updated;
            Optional<SpotlightColor> heldDye = SpotlightColor.fromDye(
                    player.getInventory().getItemInMainHand().getType()
            );
            if (heldDye.isPresent()) {
                updated = manager.setColor(current.name(), heldDye.get());
                Messages.success(
                        player,
                        updated.name() + " color set to " + heldDye.get().id().replace('_', ' ') + "."
                );
            } else if (player.isSneaking()) {
                updated = manager.toggle(current.name());
            } else {
                updated = manager.setIntensity(
                        current.name(),
                        ControllerDial.nextIntensity(current.intensity())
                );
            }
            player.playSound(
                    player.getLocation(),
                    Sound.BLOCK_LEVER_CLICK,
                    SoundCategory.BLOCKS,
                    0.7f,
                    updated.enabled() ? 1.2f : 0.8f
            );
            Messages.controllerStatus(player, updated, manager.isEffectivelyEnabled(updated));
        } catch (SpotlightManager.OperationException exception) {
            Messages.error(player, exception.getMessage());
        }
    }

    private void reconcilePiston(Collection<Block> blocks, BlockFace direction) {
        List<BlockPosition> positions = new ArrayList<>(blocks.size() * 2);
        for (Block block : blocks) {
            positions.add(toPosition(block));
            positions.add(toPosition(block.getRelative(direction)));
        }
        reconcileLater(positions);
    }

    private void reconcileLater(Collection<BlockPosition> positions) {
        eventReconciler.offer(positions);
    }

    private static void promptIfReady(Player player, SetupSession session) {
        if (session.complete()) {
            Messages.info(player, "Selections complete. Run /spotlight create <name> <circle|square> <radius>.");
        }
    }

    private static void showMarker(Player player, Block block) {
        player.spawnParticle(
                Particle.END_ROD,
                block.getLocation().add(0.5, 0.5, 0.5),
                12,
                0.18,
                0.18,
                0.18,
                0.0
        );
    }

    private static BlockPosition toPosition(Block block) {
        return new BlockPosition(
                block.getWorld().getKey().toString(),
                block.getX(),
                block.getY(),
                block.getZ()
        );
    }

    private static Plane planeFor(BlockFace face) {
        return switch (face) {
            case UP, DOWN -> Plane.XZ;
            case NORTH, SOUTH -> Plane.XY;
            case EAST, WEST -> Plane.ZY;
            default -> throw new IllegalArgumentException("Unsupported target face: " + face);
        };
    }

    private static String coordinates(BlockPosition position) {
        return position.x() + ", " + position.y() + ", " + position.z();
    }
}
