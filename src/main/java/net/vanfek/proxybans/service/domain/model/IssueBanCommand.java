package net.vanfek.proxybans.service.domain.model;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

public record IssueBanCommand(
        UUID playerUuid,
        UUID issuedByUuid,
        String issuedByName,
        String reason,
        Optional<Duration> duration
) {

    public boolean isPermanent() {
        return this.duration.isEmpty();
    }

}
