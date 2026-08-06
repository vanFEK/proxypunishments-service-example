package net.vanfek.proxypunishments.service.domain.model;

import java.util.UUID;

public record LiftPunishmentCommand(
        UUID playerUuid, PunishmentKind kind,
        UUID liftedByUuid, String liftedByName, String reason
) {

    public ActivePunishmentKey key() {
        return new ActivePunishmentKey(this.playerUuid, this.kind);
    }

}
