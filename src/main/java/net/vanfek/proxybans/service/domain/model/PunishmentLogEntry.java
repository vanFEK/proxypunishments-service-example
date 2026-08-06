package net.vanfek.proxybans.service.domain.model;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record PunishmentLogEntry(
        UUID playerUuid, UUID issuedByUuid, String issuedByName,
        PunishmentAction action, String reason,
        Instant happenedAt, Optional<Instant> expiresAt
) {

    public enum PunishmentAction {

        BAN,
        PERMBAN,
        UNBAN

    }

}
