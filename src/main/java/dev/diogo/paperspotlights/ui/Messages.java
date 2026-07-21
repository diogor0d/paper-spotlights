package dev.diogo.paperspotlights.ui;

import dev.diogo.paperspotlights.model.Spotlight;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class Messages {

    private static final Component PREFIX = Component.text("Spotlights", NamedTextColor.GOLD)
            .append(Component.text(" › ", NamedTextColor.DARK_GRAY));

    private Messages() {
    }

    public static void info(CommandSender recipient, String message) {
        recipient.sendMessage(PREFIX.append(Component.text(message, NamedTextColor.GRAY)));
    }

    public static void success(CommandSender recipient, String message) {
        recipient.sendMessage(PREFIX.append(Component.text(message, NamedTextColor.GREEN)));
    }

    public static void error(CommandSender recipient, String message) {
        recipient.sendMessage(PREFIX.append(Component.text(message, NamedTextColor.RED)));
    }

    public static void controllerStatus(Player player, Spotlight spotlight, boolean effectivelyEnabled) {
        NamedTextColor stateColor;
        String state;
        if (!spotlight.enabled()) {
            stateColor = NamedTextColor.RED;
            state = "OFF";
        } else if (spotlight.nightOnly() && !effectivelyEnabled) {
            stateColor = NamedTextColor.YELLOW;
            state = "WAITING";
        } else {
            stateColor = NamedTextColor.GREEN;
            state = "ON";
        }
        String mode = spotlight.nightOnly() ? " • AUTO" : "";
        String color = spotlight.color().id().equals("none")
                ? ""
                : " • " + spotlight.color().id().replace('_', ' ').toUpperCase(java.util.Locale.ROOT);
        player.sendActionBar(
                Component.text(spotlight.name(), NamedTextColor.GOLD)
                        .append(Component.text(" • ", NamedTextColor.DARK_GRAY))
                        .append(Component.text(state, stateColor))
                        .append(Component.text(" • " + spotlight.intensity() + "/15" + mode + color,
                                NamedTextColor.GRAY))
        );
    }
}
