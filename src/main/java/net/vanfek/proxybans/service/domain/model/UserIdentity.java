package net.vanfek.proxybans.service.domain.model;

import java.util.Locale;
import java.util.UUID;

public record UserIdentity(UUID uuid, String login) {

    /**
     * Lower-cased with an explicit locale: without one, a JVM running under a Turkish locale maps
     * "I" to a dotless "ı" and silently fails to match names.
     */
    public String loginKey() {
        return this.login.toLowerCase(Locale.ROOT);
    }

}
