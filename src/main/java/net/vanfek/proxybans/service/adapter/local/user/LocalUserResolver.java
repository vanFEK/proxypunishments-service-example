package net.vanfek.proxybans.service.adapter.local.user;

import net.vanfek.proxybans.service.domain.model.UserIdentity;
import net.vanfek.proxybans.service.domain.port.UserResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.Locale;

@Repository
@ConditionalOnProperty(name = "proxybans.mode", havingValue = "local")
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
    public Mono<Void> remember(UserIdentity identity) {
        return this.players.upsert(identity.uuid(), identity.login(), identity.loginKey(), this.clock.instant()).then();
    }

}
