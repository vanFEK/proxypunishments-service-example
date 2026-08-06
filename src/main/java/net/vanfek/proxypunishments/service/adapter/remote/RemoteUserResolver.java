package net.vanfek.proxypunishments.service.adapter.remote;

import net.vanfek.proxypunishments.service.adapter.remote.RemotePayloads.IdentityPayload;
import net.vanfek.proxypunishments.service.config.ApplicationProperties;
import net.vanfek.proxypunishments.service.domain.model.UserIdentity;
import net.vanfek.proxypunishments.service.domain.port.UserResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

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

    @Override
    public Mono<Void> remember(UserIdentity identity) {
        return Mono.empty();
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
