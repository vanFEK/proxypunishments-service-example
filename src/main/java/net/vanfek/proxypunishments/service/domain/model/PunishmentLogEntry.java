package net.vanfek.proxypunishments.service.domain.model;

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
        UNBAN,
        MUTE,
        PERMMUTE,
        UNMUTE,
        IPBAN,
        PERMIPBAN,
        UNIPBAN,
        WARN

    }

}
