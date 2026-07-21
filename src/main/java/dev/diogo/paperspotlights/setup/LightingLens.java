package dev.diogo.paperspotlights.setup;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Map;

/** A tagged vanilla Light item: it also reveals invisible LIGHT blocks. */
public final class LightingLens {

    private final NamespacedKey wandKey;

    public LightingLens(NamespacedKey wandKey) {
        this.wandKey = wandKey;
    }

    public boolean isLens(ItemStack item) {
        if (item == null || item.getType() != Material.LIGHT) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta.getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE);
    }

    public ItemStack create() {
        ItemStack item = new ItemStack(Material.LIGHT);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Gaffer's Lens", NamedTextColor.GOLD));
        meta.lore(List.of(
                Component.text("Left-click: select beam origin", NamedTextColor.GRAY),
                Component.text("Right-click block: select area plane", NamedTextColor.GRAY),
                Component.text("Right-click clock frame: select dimmer", NamedTextColor.GRAY),
                Component.text("Holding this reveals invisible light blocks", NamedTextColor.DARK_GRAY)
        ));
        meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public void giveTo(Player player) {
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(create());
        for (ItemStack leftover : leftovers.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }
}

