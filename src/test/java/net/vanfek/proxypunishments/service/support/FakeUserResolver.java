package net.vanfek.proxypunishments.service.support;

import net.vanfek.proxypunishments.service.domain.model.UserIdentity;
import net.vanfek.proxypunishments.service.domain.port.UserResolver;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class FakeUserResolver implements UserResolver {

    private final Map<String, UserIdentity> identities = new HashMap<>();
    private final AtomicInteger resolveCalls = new AtomicInteger();

    public int resolveCalls() {
        return this.resolveCalls.get();
    }

    public void register(UserIdentity identity) {
        this.identities.put(identity.loginKey(), identity);
    }

    @Override
    public Mono<UserIdentity> resolveByLogin(String login) {
        this.resolveCalls.incrementAndGet();

        return Mono.justOrEmpty(this.identities.get(login.toLowerCase(Locale.ROOT)));
    }

    @Override
    public Mono<Void> remember(UserIdentity identity) {
        register(identity);

        return Mono.empty();
    }

}
