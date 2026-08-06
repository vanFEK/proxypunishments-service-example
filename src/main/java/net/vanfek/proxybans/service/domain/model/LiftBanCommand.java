package net.vanfek.proxybans.service.domain.model;

import java.util.UUID;

public record LiftBanCommand(UUID playerUuid, UUID liftedByUuid, String liftedByName, String reason) {
}
