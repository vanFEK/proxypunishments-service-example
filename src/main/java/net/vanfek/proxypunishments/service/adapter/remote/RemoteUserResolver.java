package net.vanfek.proxypunishments.service.adapter.remote;

import net.vanfek.proxypunishments.service.adapter.remote.RemotePayloads.IdentityPayload;
import net.vanfek.proxypunishments.service.config.ApplicationProperties;
import net.vanfek.proxypunishments.service.domain.model.UserIdentity;
import net.vanfek.proxypunishments.service.domain.model.ip.Alt;
import net.vanfek.proxypunishments.service.domain.model.ip.IpAddress;
import net.vanfek.proxypunishments.service.domain.port.UserResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.UUID;

@Repository
@ConditionalOnProperty(name = "proxypunishments.mode", havingValue = "remote")
class RemoteUserResolver implements UserResolver {

    private final WebClient client;
    private final String lookupPath;

    RemoteUserResolver(WebClient remoteWebClient, ApplicationProperties properties) {
        this.client = remoteWebClient;
        this.lookupPath = properties.remote().paths().lookup();
    }

    @Override
    public Mono<UserIdentity> resolveByLogin(String login) {
        return this.client.get()
                .uri(this.lookupPath, login)
                .retrieve()
                .bodyToMono(IdentityPayload.class)
                .mapNotNull(RemoteUserResolver::toIdentity)
                .onErrorResume(RemoteUserResolver::isMissing, ignored -> Mono.empty());
    }

    // known_players is a local-mode table; a remote backend keeps its own sightings and shares no addresses with us.
    @Override
    public Mono<Void> remember(UserIdentity identity, Optional<IpAddress> ipAddress) {
        return Mono.empty();
    }

    @Override
    public Flux<Alt> findAlts(UUID playerUuid, int limit) {
        return Flux.empty();
    }

    private static UserIdentity toIdentity(IdentityPayload payload) {
        if (payload.uuid() == null) {
            return null;
        }

        try {
            return new UserIdentity(UUID.fromString(payload.uuid()), payload.login());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static boolean isMissing(Throwable throwable) {
        return throwable instanceof WebClientResponseException exception
                && exception.getStatusCode() == HttpStatus.NOT_FOUND;
    }

}
