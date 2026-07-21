package dev.diogo.paperspotlights;

import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PluginDescriptorTest {

    @Test
    void filteredDescriptorParsesAsTheExpectedPaperPlugin() throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("plugin.yml")) {
            assertNotNull(input);
            PluginDescriptionFile descriptor = new PluginDescriptionFile(input);

            assertEquals("PaperSpotlights", descriptor.getName());
            assertEquals(System.getProperty("projectVersion"), descriptor.getVersion());
            assertEquals(PaperSpotlightsPlugin.class.getName(), descriptor.getMainClass());
            assertEquals("26.2", descriptor.getAPIVersion());
        }
    }
}
