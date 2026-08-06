package net.vanfek.proxybans.service.domain.model;

import java.util.UUID;

// The cache is keyed by player *and* kind: a mute must never evict or masquerade as that player's ban.
public record ActivePunishmentKey(UUID playerUuid, PunishmentKind kind) {
}
