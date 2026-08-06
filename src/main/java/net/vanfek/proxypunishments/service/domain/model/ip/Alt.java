package net.vanfek.proxypunishments.service.domain.model.ip;

import java.time.Instant;
import java.util.UUID;

public record Alt(UUID uuid, String login, Instant lastSeenAt, boolean sharedIp) {
}
