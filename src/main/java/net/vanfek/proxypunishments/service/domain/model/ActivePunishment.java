package net.vanfek.proxypunishments.service.domain.model;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record ActivePunishment(
        long id, UUID playerUuid, Optional<String> playerName,
        PunishmentKind kind,
        UUID issuedByUuid, String issuedByName,
        String reason,
        Instant issuedAt, Optional<Instant> expiresAt,
        BanType type
) {

    public boolean isExpired(Instant now) {
        return this.expiresAt.map(now::isAfter).orElse(false);
    }

    public boolean isPermanent() {
        return this.type == BanType.PERMANENT;
    }

    public ActivePunishmentKey key() {
        return new ActivePunishmentKey(this.playerUuid, this.kind);
    }

}
