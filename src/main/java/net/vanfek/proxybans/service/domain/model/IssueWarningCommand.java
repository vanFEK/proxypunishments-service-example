package net.vanfek.proxybans.service.domain.model;

import java.util.UUID;

public record IssueWarningCommand(UUID playerUuid, UUID issuedByUuid, String issuedByName, String reason) {
}
