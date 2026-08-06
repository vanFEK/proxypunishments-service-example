package net.vanfek.proxypunishments.service.domain.model;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

public record IssuePunishmentCommand(
        UUID playerUuid, PunishmentKind kind,
        UUID issuedByUuid, String issuedByName,
        String reason, Optional<Duration> duration
) {

    public boolean isPermanent() {
        return this.duration.isEmpty();
    }

    public ActivePunishmentKey key() {
        return new ActivePunishmentKey(this.playerUuid, this.kind);
    }

}
