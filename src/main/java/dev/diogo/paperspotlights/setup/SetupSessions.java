package dev.diogo.paperspotlights.setup;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class SetupSessions {

    private final Map<UUID, SetupSession> sessions = new HashMap<>();

    public SetupSession getOrCreate(UUID playerUuid) {
        return sessions.computeIfAbsent(playerUuid, ignored -> new SetupSession());
    }

    public Optional<SetupSession> get(UUID playerUuid) {
        return Optional.ofNullable(sessions.get(playerUuid));
    }

    public boolean clear(UUID playerUuid) {
        return sessions.remove(playerUuid) != null;
    }
}

