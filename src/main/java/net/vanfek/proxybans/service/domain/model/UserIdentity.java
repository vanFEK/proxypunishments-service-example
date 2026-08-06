package net.vanfek.proxybans.service.domain.model;

import java.util.Locale;
import java.util.UUID;

public record UserIdentity(UUID uuid, String login) {

    // Locale.ROOT matters: under a Turkish locale "I" lower-cases to a dotless "ı" and names stop matching.
    public String loginKey() {
        return this.login.toLowerCase(Locale.ROOT);
    }

}
