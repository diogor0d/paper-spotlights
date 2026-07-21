package dev.diogo.paperspotlights.color;

import dev.diogo.paperspotlights.model.SpotlightColor;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpotlightColorTest {

    @Test
    void everyVanillaDyeHasOneStableColor() {
        assertEquals(17, SpotlightColor.values().length);
        assertEquals(
                17,
                Arrays.stream(SpotlightColor.values()).map(SpotlightColor::id).distinct().count()
        );
        assertEquals(
                16,
                Arrays.stream(SpotlightColor.values()).flatMap(color -> color.dye().stream()).distinct().count()
        );
    }

    @Test
    void parsingAcceptsCommandFriendlySeparatorsAndBritishAliases() {
        assertEquals(SpotlightColor.LIGHT_BLUE, SpotlightColor.parse(" light-blue ").orElseThrow());
        assertEquals(SpotlightColor.LIGHT_GRAY, SpotlightColor.parse("light grey").orElseThrow());
        assertEquals(SpotlightColor.GRAY, SpotlightColor.parse("grey").orElseThrow());
        assertEquals(SpotlightColor.NONE, SpotlightColor.parse("off").orElseThrow());
        assertTrue(SpotlightColor.NONE.particleColor().isEmpty());
        assertTrue(SpotlightColor.NONE.dye().isEmpty());
        assertTrue(SpotlightColor.parse("ultraviolet").isEmpty());
    }

    @Test
    void dyeLookupRejectsNonDyes() {
        assertEquals(SpotlightColor.RED, SpotlightColor.fromDye(Material.RED_DYE).orElseThrow());
        assertTrue(SpotlightColor.fromDye(Material.RED_WOOL).isEmpty());
    }
}
