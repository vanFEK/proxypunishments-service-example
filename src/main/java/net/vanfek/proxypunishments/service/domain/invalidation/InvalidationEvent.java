package net.vanfek.proxypunishments.service.domain.invalidation;

import net.vanfek.proxypunishments.service.domain.model.PunishmentKind;
import net.vanfek.proxypunishments.service.domain.model.ip.IpAddress;

import java.util.UUID;

public record InvalidationEvent(String target, PunishmentKind kind) {

    public static InvalidationEvent forPlayer(UUID playerUuid, PunishmentKind kind) {
        return new InvalidationEvent(playerUuid.toString(), kind);
    }

    public static InvalidationEvent forAddress(IpAddress ipAddress) {
        return new InvalidationEvent(ipAddress.asText(), PunishmentKind.IP_BAN);
    }

    public boolean targetsAddress() {
        return this.kind.targetsAddress();
    }

}
