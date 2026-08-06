package net.vanfek.proxypunishments.service.domain.port;

import net.vanfek.proxypunishments.service.domain.model.UserIdentity;
import net.vanfek.proxypunishments.service.domain.model.ip.Alt;
import net.vanfek.proxypunishments.service.domain.model.ip.IpAddress;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.UUID;

public interface UserResolver {

    Mono<UserIdentity> resolveByLogin(String login);

    Mono<Void> remember(UserIdentity identity, Optional<IpAddress> ipAddress);

    Flux<Alt> findAlts(UUID playerUuid, int limit);

}
