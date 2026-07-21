package dev.diogo.paperspotlights.model;

import org.bukkit.Color;
import org.bukkit.Material;

import java.util.Locale;
import java.util.Optional;

/**
 * Stable, persistence-friendly spotlight palette based on vanilla dye names.
 */
public enum SpotlightColor {
    NONE("none", null, null),
    WHITE("white", 0xF9FFFE, Material.WHITE_DYE),
    ORANGE("orange", 0xF9801D, Material.ORANGE_DYE),
    MAGENTA("magenta", 0xC74EBD, Material.MAGENTA_DYE),
    LIGHT_BLUE("light_blue", 0x3AB3DA, Material.LIGHT_BLUE_DYE),
    YELLOW("yellow", 0xFED83D, Material.YELLOW_DYE),
    LIME("lime", 0x80C71F, Material.LIME_DYE),
    PINK("pink", 0xF38BAA, Material.PINK_DYE),
    GRAY("gray", 0x474F52, Material.GRAY_DYE),
    LIGHT_GRAY("light_gray", 0x9D9D97, Material.LIGHT_GRAY_DYE),
    CYAN("cyan", 0x169C9C, Material.CYAN_DYE),
    PURPLE("purple", 0x8932B8, Material.PURPLE_DYE),
    BLUE("blue", 0x3C44AA, Material.BLUE_DYE),
    BROWN("brown", 0x835432, Material.BROWN_DYE),
    GREEN("green", 0x5E7C16, Material.GREEN_DYE),
    RED("red", 0xB02E26, Material.RED_DYE),
    BLACK("black", 0x1D1D21, Material.BLACK_DYE);

    private final String id;
    private final Color particleColor;
    private final Material dye;

    SpotlightColor(String id, Integer rgb, Material dye) {
        this.id = id;
        this.particleColor = rgb == null ? null : Color.fromRGB(rgb);
        this.dye = dye;
    }

    public String id() {
        return id;
    }

    public Optional<Color> particleColor() {
        return Optional.ofNullable(particleColor);
    }

    public Optional<Material> dye() {
        return Optional.ofNullable(dye);
    }

    public static Optional<SpotlightColor> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.trim()
                .toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        normalized = switch (normalized) {
            case "off", "clear" -> "none";
            case "grey" -> "gray";
            case "light_grey", "silver" -> "light_gray";
            default -> normalized;
        };
        for (SpotlightColor color : values()) {
            if (color.id.equals(normalized)) {
                return Optional.of(color);
            }
        }
        return Optional.empty();
    }

    public static Optional<SpotlightColor> fromDye(Material material) {
        if (material == null) {
            return Optional.empty();
        }
        for (SpotlightColor color : values()) {
            if (color.dye == material) {
                return Optional.of(color);
            }
        }
        return Optional.empty();
    }
}
