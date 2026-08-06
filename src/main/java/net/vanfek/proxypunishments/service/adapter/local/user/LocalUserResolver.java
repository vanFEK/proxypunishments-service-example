package net.vanfek.proxypunishments.service.adapter.local.user;

import net.vanfek.proxypunishments.service.domain.model.UserIdentity;
import net.vanfek.proxypunishments.service.domain.model.ip.Alt;
import net.vanfek.proxypunishments.service.domain.model.ip.IpAddress;
import net.vanfek.proxypunishments.service.domain.port.UserResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Repository
@ConditionalOnProperty(name = "proxypunishments.mode", havingValue = "local")
class LocalUserResolver implements UserResolver {

    private final KnownPlayerRowRepository players;
    private final Clock clock;

    LocalUserResolver(KnownPlayerRowRepository players, Clock clock) {
        this.players = players;
        this.clock = clock;
    }

    @Override
    public Mono<UserIdentity> resolveByLogin(String login) {
        return this.players.findByLoginLower(login.toLowerCase(Locale.ROOT))
                .map(row -> new UserIdentity(row.uuid(), row.login()));
    }

    @Override
    public Mono<Void> remember(UserIdentity identity, Optional<IpAddress> ipAddress) {
        String lastIp = ipAddress.map(IpAddress::asText).orElse(null);

        return this.players
                .upsert(identity.uuid(), identity.login(), identity.loginKey(), this.clock.instant(), lastIp)
                .then();
    }

    @Override
    public Flux<Alt> findAlts(UUID playerUuid, int limit) {
        return this.players.findAlts(playerUuid, limit)
                .map(row -> new Alt(row.uuid(), row.login(), row.seenAt(), true));
    }

}
