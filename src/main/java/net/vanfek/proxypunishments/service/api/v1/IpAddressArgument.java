package net.vanfek.proxypunishments.service.api.v1;

import net.vanfek.proxypunishments.service.domain.model.ip.IpAddress;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

// Addresses are rejected here rather than handed to the database, wherever one arrives from a caller.
final class IpAddressArgument {

    private IpAddressArgument() {
    }

    static IpAddress required(String raw) {
        return IpAddress.parse(raw).orElseThrow(IpAddressArgument::reject);
    }

    static Optional<IpAddress> optional(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }

        return Optional.of(required(raw));
    }

    private static ResponseStatusException reject() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "ipAddress must be a literal IPv4 or IPv6 address");
    }

}
