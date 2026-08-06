package net.vanfek.proxypunishments.service.domain.model.ip;

import java.net.InetAddress;
import java.util.Optional;

// InetAddress.ofLiteral rejects anything that is not a literal address without ever resolving a name.
public record IpAddress(InetAddress value) {

    public static Optional<IpAddress> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }

        try {
            return Optional.of(new IpAddress(InetAddress.ofLiteral(raw.trim())));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    public String asText() {
        return this.value.getHostAddress();
    }

}
