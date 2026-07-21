package dev.diogo.paperspotlights;

import dev.diogo.paperspotlights.model.Shape;
import dev.diogo.paperspotlights.model.Spotlight;
import dev.diogo.paperspotlights.model.SpotlightColor;
import dev.diogo.paperspotlights.setup.LightingLens;
import dev.diogo.paperspotlights.setup.SetupSession;
import dev.diogo.paperspotlights.setup.SetupSessions;
import dev.diogo.paperspotlights.ui.Messages;
import dev.diogo.paperspotlights.ui.PreviewService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;

import java.io.Serial;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SpotlightCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of(
            "help", "wand", "create", "cancel", "list", "info", "toggle", "level", "color", "auto", "remove"
    );

    private final SpotlightManager manager;
    private final SetupSessions sessions;
    private final LightingLens lens;
    private final PreviewService preview;

    public SpotlightCommand(
            SpotlightManager manager,
            SetupSessions sessions,
            LightingLens lens,
            PreviewService preview
    ) {
        this.manager = manager;
        this.sessions = sessions;
        this.lens = lens;
        this.preview = preview;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            showHelp(sender);
            return true;
        }

        try {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "wand" -> giveLens(requirePlayer(sender));
                case "create" -> create(requirePlayer(sender), args);
                case "cancel" -> cancel(requirePlayer(sender));
                case "list" -> list(sender);
                case "info" -> info(sender, args);
                case "toggle" -> toggle(sender, args);
                case "level" -> level(sender, args);
                case "color" -> color(sender, args);
                case "auto" -> auto(sender, args);
                case "remove" -> remove(sender, args);
                default -> Messages.error(sender, "Unknown subcommand. Run /spotlight help.");
            }
        } catch (CommandFailure exception) {
            Messages.error(sender, exception.getMessage());
        } catch (SpotlightManager.OperationException exception) {
            Messages.error(sender, exception.getMessage());
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args
    ) {
        if (args.length == 1) {
            return matching(SUBCOMMANDS, args[0]);
        }
        if (args.length == 2
                && List.of("info", "toggle", "level", "color", "auto", "remove")
                .contains(args[0].toLowerCase(Locale.ROOT))) {
            return matching(manager.all().stream().map(Spotlight::name).toList(), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("create")) {
            return matching(List.of("circle", "square"), args[2]);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("create")) {
            return matching(List.of("3", "5", "8", Integer.toString(manager.maxRadius())), args[3]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("level")) {
            return matching(List.of("1", "3", "5", "7", "9", "11", "13", "15"), args[2]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("color")) {
            return matching(
                    java.util.Arrays.stream(SpotlightColor.values()).map(SpotlightColor::id).toList(),
                    args[2]
            );
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("auto")) {
            return matching(List.of("on", "off"), args[2]);
        }
        return List.of();
    }

    private void giveLens(Player player) {
        lens.giveTo(player);
        Messages.success(player, "Gaffer's Lens added to your inventory. Hold it to reveal LIGHT blocks.");
        Messages.info(player, "Left-click origin, right-click target surface, then right-click a clock frame.");
    }

    private void create(Player player, String[] args)
            throws CommandFailure, SpotlightManager.OperationException {
        if (args.length != 4) {
            throw new CommandFailure("Usage: /spotlight create <name> <circle|square> <radius>");
        }

        Shape shape;
        int radius;
        try {
            shape = Shape.parse(args[2]);
        } catch (IllegalArgumentException exception) {
            throw new CommandFailure("Shape must be circle or square.");
        }
        try {
            radius = Integer.parseInt(args[3]);
        } catch (NumberFormatException exception) {
            throw new CommandFailure("Radius must be a whole number.");
        }

        SetupSession setup = sessions.get(player.getUniqueId())
                .filter(SetupSession::complete)
                .orElseThrow(() -> new CommandFailure(
                        "Setup is incomplete. Use /spotlight wand and make all three selections."
                ));
        Entity entity = Bukkit.getEntity(setup.controllerUuid().orElseThrow());
        if (!(entity instanceof ItemFrame frame) || !entity.isValid()) {
            throw new CommandFailure("The selected clock frame is no longer loaded; select it again.");
        }

        String worldKey = setup.origin().orElseThrow().worldKey();
        if (!setup.target().orElseThrow().worldKey().equals(worldKey)
                || !setup.controllerWorldKey().orElseThrow().equals(worldKey)) {
            throw new CommandFailure("Origin, target, and controller must be in the same world.");
        }

        Spotlight created = manager.create(
                args[1],
                setup.origin().orElseThrow(),
                setup.target().orElseThrow(),
                setup.plane().orElseThrow(),
                shape,
                radius,
                frame
        );
        sessions.clear(player.getUniqueId());
        Messages.success(
                player,
                "Created '" + created.name() + "'. Click its clock to dim; sneak-click to toggle."
        );
        preview.show(player, created);
    }

    private void cancel(Player player) {
        if (sessions.clear(player.getUniqueId())) {
            Messages.success(player, "Cleared your setup selections.");
        } else {
            Messages.info(player, "You had no setup in progress.");
        }
    }

    private void list(CommandSender sender) {
        List<Spotlight> spotlights = manager.all();
        if (spotlights.isEmpty()) {
            Messages.info(sender, "No spotlights exist yet. Run /spotlight wand to begin.");
            return;
        }
        Messages.info(sender, "Spotlights (" + spotlights.size() + "):");
        for (Spotlight spotlight : spotlights) {
            String state = stateLabel(spotlight);
            String mode = spotlight.nightOnly() ? "AUTO" : "MANUAL";
            Messages.info(
                    sender,
                    "- " + spotlight.name() + ": " + state + " " + spotlight.intensity()
                            + "/15, " + spotlight.shape().name().toLowerCase(Locale.ROOT)
                            + " r=" + spotlight.radius() + ", " + mode
                            + ", color=" + spotlight.color().id()
            );
        }
    }

    private void info(CommandSender sender, String[] args) throws CommandFailure {
        Spotlight spotlight = named(args, "Usage: /spotlight info <name>");
        Messages.info(sender, spotlight.name() + " — " + stateLabel(spotlight)
                + " at " + spotlight.intensity() + "/15");
        Messages.info(sender, "Mode: " + (spotlight.nightOnly() ? "automatic night-only" : "manual")
                + (spotlight.enabled() ? " (armed)" : " (switched off)")
                + ", color " + spotlight.color().id());
        Messages.info(sender, "Shape: " + spotlight.shape().name().toLowerCase(Locale.ROOT)
                + ", radius " + spotlight.radius() + ", plane " + spotlight.plane());
        Messages.info(sender, "Origin: " + coordinates(spotlight.origin().x(), spotlight.origin().y(), spotlight.origin().z())
                + " • Target: " + coordinates(spotlight.target().x(), spotlight.target().y(), spotlight.target().z()));
        if (sender instanceof Player player) {
            preview.show(player, spotlight);
        }
    }

    private void toggle(CommandSender sender, String[] args)
            throws CommandFailure, SpotlightManager.OperationException {
        Spotlight current = named(args, "Usage: /spotlight toggle <name>");
        Spotlight updated = manager.toggle(current.name());
        if (updated.nightOnly()) {
            Messages.success(
                    sender,
                    updated.name() + " is now " + (updated.enabled() ? "armed for night." : "disarmed.")
            );
        } else {
            Messages.success(sender, updated.name() + " is now " + (updated.enabled() ? "ON" : "OFF") + ".");
        }
    }

    private void level(CommandSender sender, String[] args)
            throws CommandFailure, SpotlightManager.OperationException {
        if (args.length != 3) {
            throw new CommandFailure("Usage: /spotlight level <name> <1-15>");
        }
        int intensity;
        try {
            intensity = Integer.parseInt(args[2]);
        } catch (NumberFormatException exception) {
            throw new CommandFailure("Intensity must be a whole number from 1 to 15.");
        }
        Spotlight updated = manager.setIntensity(args[1], intensity);
        Messages.success(sender, updated.name() + " intensity is now " + updated.intensity() + "/15.");
    }

    private void color(CommandSender sender, String[] args)
            throws CommandFailure, SpotlightManager.OperationException {
        if (args.length != 3) {
            throw new CommandFailure("Usage: /spotlight color <name> <none|dye-color>");
        }
        SpotlightColor color = SpotlightColor.parse(args[2])
                .orElseThrow(() -> new CommandFailure("Unknown dye color. Use tab completion or 'none'."));
        Spotlight updated = manager.setColor(requiredNamed(args[1]), color);
        String description = color == SpotlightColor.NONE
                ? "now uses plain native lighting"
                : "now uses a " + color.id().replace('_', ' ') + " color wash";
        Messages.success(sender, updated.name() + " " + description + ".");
    }

    private void auto(CommandSender sender, String[] args)
            throws CommandFailure, SpotlightManager.OperationException {
        if (args.length != 3) {
            throw new CommandFailure("Usage: /spotlight auto <name> <on|off>");
        }
        boolean nightOnly = switch (args[2].toLowerCase(Locale.ROOT)) {
            case "on" -> true;
            case "off" -> false;
            default -> throw new CommandFailure("Automatic mode must be 'on' or 'off'.");
        };
        Spotlight updated = manager.setNightOnly(requiredNamed(args[1]), nightOnly);
        Messages.success(
                sender,
                updated.name() + (nightOnly
                        ? " will now illuminate only from nightfall to dawn."
                        : " is now manually controlled at all times.")
        );
    }

    private void remove(CommandSender sender, String[] args)
            throws CommandFailure, SpotlightManager.OperationException {
        Spotlight current = named(args, "Usage: /spotlight remove <name>");
        manager.remove(current.name());
        Messages.success(sender, "Removed spotlight '" + current.name() + "'. The clock frame was kept.");
    }

    private Spotlight named(String[] args, String usage) throws CommandFailure {
        if (args.length != 2) {
            throw new CommandFailure(usage);
        }
        return manager.findByName(args[1])
                .orElseThrow(() -> new CommandFailure("No spotlight named '" + args[1] + "'."));
    }

    private String requiredNamed(String name) throws CommandFailure {
        return manager.findByName(name)
                .orElseThrow(() -> new CommandFailure("No spotlight named '" + name + "'."))
                .name();
    }

    private String stateLabel(Spotlight spotlight) {
        if (!spotlight.enabled()) {
            return "OFF";
        }
        if (spotlight.nightOnly() && !manager.isEffectivelyEnabled(spotlight)) {
            return "WAITING";
        }
        return "ON";
    }

    private static Player requirePlayer(CommandSender sender) throws CommandFailure {
        if (sender instanceof Player player) {
            return player;
        }
        throw new CommandFailure("That command must be run by a player.");
    }

    private static void showHelp(CommandSender sender) {
        Messages.info(sender, "/spotlight wand — get the setup lens");
        Messages.info(sender, "/spotlight create <name> <circle|square> <radius>");
        Messages.info(sender, "/spotlight list | info <name> | toggle <name>");
        Messages.info(sender, "/spotlight level <name> <1-15> | color <name> <none|color>");
        Messages.info(sender, "/spotlight auto <name> <on|off> | remove <name> | cancel");
        Messages.info(sender, "Clock frame: click to dim, sneak-click to toggle, break to remove.");
    }

    private static List<String> matching(List<String> candidates, String prefix) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String candidate : candidates) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith(normalized)) {
                result.add(candidate);
            }
        }
        return List.copyOf(result);
    }

    private static String coordinates(int x, int y, int z) {
        return x + ", " + y + ", " + z;
    }

    private static final class CommandFailure extends Exception {

        @Serial
        private static final long serialVersionUID = 1L;

        private CommandFailure(String message) {
            super(message);
        }
    }
}
