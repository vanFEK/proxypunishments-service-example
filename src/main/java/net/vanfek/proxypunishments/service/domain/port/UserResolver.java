package net.vanfek.proxypunishments.service.domain.port;

import net.vanfek.proxypunishments.service.domain.model.UserIdentity;
import reactor.core.publisher.Mono;

public interface UserResolver {

    Mono<UserIdentity> resolveByLogin(String login);

    Mono<Void> remember(UserIdentity identity);

}
