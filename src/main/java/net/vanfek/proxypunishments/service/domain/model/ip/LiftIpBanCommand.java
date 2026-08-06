package net.vanfek.proxypunishments.service.domain.model.ip;

import java.util.UUID;

public record LiftIpBanCommand(
        IpAddress ipAddress, UUID liftedByUuid,
        String liftedByName, String reason
) {
}
