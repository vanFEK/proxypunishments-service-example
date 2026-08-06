package net.vanfek.proxypunishments.service.adapter.redis;

import net.vanfek.proxypunishments.service.domain.invalidation.InvalidationEvent;
import net.vanfek.proxypunishments.service.domain.model.PunishmentKind;

import java.util.Optional;

public record InvalidationMessage(String target, String kind, String origin) {

    public static InvalidationMessage of(InvalidationEvent event, String origin) {
        return new InvalidationMessage(event.target(), event.kind().name(), origin);
    }

    public Optional<InvalidationEvent> toEvent() {
        if (this.target == null || this.target.isBlank() || this.kind == null) {
            return Optional.empty();
        }

        try {
            return Optional.of(new InvalidationEvent(this.target, PunishmentKind.valueOf(this.kind)));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

}
