package dev.diogo.paperspotlights.setup;

import dev.diogo.paperspotlights.model.BlockPosition;
import dev.diogo.paperspotlights.model.Plane;

import java.util.Optional;
import java.util.UUID;

/** Per-player, non-persisted setup selections. */
public final class SetupSession {

    private BlockPosition origin;
    private BlockPosition target;
    private Plane plane;
    private UUID controllerUuid;
    private String controllerWorldKey;

    public Optional<BlockPosition> origin() {
        return Optional.ofNullable(origin);
    }

    public void origin(BlockPosition value) {
        origin = value;
    }

    public Optional<BlockPosition> target() {
        return Optional.ofNullable(target);
    }

    public Optional<Plane> plane() {
        return Optional.ofNullable(plane);
    }

    public void target(BlockPosition value, Plane valuePlane) {
        target = value;
        plane = valuePlane;
    }

    public Optional<UUID> controllerUuid() {
        return Optional.ofNullable(controllerUuid);
    }

    public Optional<String> controllerWorldKey() {
        return Optional.ofNullable(controllerWorldKey);
    }

    public void controller(UUID value, String worldKey) {
        controllerUuid = value;
        controllerWorldKey = worldKey;
    }

    public boolean complete() {
        return origin != null
                && target != null
                && plane != null
                && controllerUuid != null
                && controllerWorldKey != null;
    }
}

