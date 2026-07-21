package dev.diogo.paperspotlights.persistence;

import dev.diogo.paperspotlights.model.BlockPosition;
import dev.diogo.paperspotlights.model.Plane;
import dev.diogo.paperspotlights.model.Shape;
import dev.diogo.paperspotlights.model.Spotlight;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpotlightRepositoryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsDefinitionsAndManagedBaselines() throws IOException {
        SpotlightRepository repository = new SpotlightRepository(temporaryDirectory);
        BlockPosition origin = new BlockPosition("minecraft:overworld", -5, 70, 12);
        BlockPosition target = new BlockPosition("minecraft:overworld", 4, 64, 9);
        Spotlight spotlight = new Spotlight(
                UUID.randomUUID(),
                "stage-left",
                origin,
                target,
                Plane.XZ,
                Shape.CIRCLE,
                6,
                13,
                true,
                UUID.randomUUID()
        );

        repository.save(List.of(spotlight), Map.of(target, "minecraft:air"));
        SpotlightRepository.State loaded = repository.load();

        assertEquals(List.of(spotlight), loaded.spotlights());
        assertEquals(Map.of(target, "minecraft:air"), loaded.managedLights());
    }

    @Test
    void keepsPreviousAtomicStateAsBackup() throws IOException {
        SpotlightRepository repository = new SpotlightRepository(temporaryDirectory);
        BlockPosition position = new BlockPosition("minecraft:overworld", 1, 64, 2);
        repository.save(List.of(), Map.of(position, "minecraft:air"));
        String previousState = Files.readString(temporaryDirectory.resolve("state.yml"));
        repository.save(List.of(), Map.of());

        assertTrue(Files.isRegularFile(temporaryDirectory.resolve("state.yml")));
        assertTrue(Files.isRegularFile(temporaryDirectory.resolve("state.yml.bak")));
        assertEquals(previousState, Files.readString(temporaryDirectory.resolve("state.yml.bak")));
    }

    @Test
    void rejectsCorruptYamlWithoutFallingBackSilently() throws IOException {
        SpotlightRepository repository = new SpotlightRepository(temporaryDirectory);
        Files.writeString(temporaryDirectory.resolve("state.yml"), "[not: valid: yaml");

        assertThrows(IOException.class, repository::load);
    }

    @Test
    void rejectsUnsupportedSchema() throws IOException {
        SpotlightRepository repository = new SpotlightRepository(temporaryDirectory);
        Files.writeString(temporaryDirectory.resolve("state.yml"), "schema-version: 999\n");

        assertThrows(IOException.class, repository::load);
    }
}
