package dev.diogo.paperspotlights.persistence;

import dev.diogo.paperspotlights.model.BlockPosition;
import dev.diogo.paperspotlights.model.Plane;
import dev.diogo.paperspotlights.model.Shape;
import dev.diogo.paperspotlights.model.Spotlight;
import dev.diogo.paperspotlights.model.SpotlightColor;
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
        assertTrue(!loaded.migrationRequired());
    }

    @Test
    void loadsSchemaOneWithSafeDefaultsAndRequestsMigration() throws IOException {
        UUID spotlightId = UUID.randomUUID();
        UUID controllerId = UUID.randomUUID();
        Files.writeString(temporaryDirectory.resolve("state.yml"), """
                schema-version: 1
                spotlights:
                  %s:
                    name: legacy
                    world: minecraft:overworld
                    origin: {x: 0, y: 70, z: 0}
                    target: {x: 0, y: 64, z: 0}
                    plane: XZ
                    shape: CIRCLE
                    radius: 4
                    intensity: 15
                    enabled: true
                    controller-uuid: %s
                managed-lights: {}
                """.formatted(spotlightId, controllerId));

        SpotlightRepository.State state = new SpotlightRepository(temporaryDirectory).load();

        assertTrue(state.migrationRequired());
        assertEquals(SpotlightColor.NONE, state.spotlights().getFirst().color());
        assertTrue(!state.spotlights().getFirst().nightOnly());
    }

    @Test
    void rejectsSchemaTwoWithoutNewRequiredFields() throws IOException {
        Files.writeString(temporaryDirectory.resolve("state.yml"), """
                schema-version: 2
                spotlights:
                  9d57c4d2-43cd-4b08-9d47-f92569cf4c64:
                    name: incomplete
                    world: minecraft:overworld
                    origin: {x: 0, y: 70, z: 0}
                    target: {x: 0, y: 64, z: 0}
                    plane: XZ
                    shape: CIRCLE
                    radius: 4
                    intensity: 15
                    enabled: true
                    controller-uuid: f091279e-a9d2-4f07-87ea-700e6508fb86
                managed-lights: {}
                """);

        assertThrows(IOException.class, new SpotlightRepository(temporaryDirectory)::load);
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
