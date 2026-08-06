package net.vanfek.proxypunishments.service.support;

import net.vanfek.proxypunishments.service.domain.model.UserIdentity;
import net.vanfek.proxypunishments.service.domain.model.ip.Alt;
import net.vanfek.proxypunishments.service.domain.model.ip.IpAddress;
import net.vanfek.proxypunishments.service.domain.port.UserResolver;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public final class FakeUserResolver implements UserResolver {

    private final Map<String, UserIdentity> identities = new HashMap<>();
    private final Map<UUID, Sighting> sightings = new HashMap<>();
    private final AtomicInteger resolveCalls = new AtomicInteger();
    private final Clock clock;

    public FakeUserResolver() {
        this(Clock.systemUTC());
    }

    public FakeUserResolver(Clock clock) {
        this.clock = clock;
    }

    public int resolveCalls() {
        return this.resolveCalls.get();
    }

    public void register(UserIdentity identity) {
        this.identities.put(identity.loginKey(), identity);
    }

    public void seen(UserIdentity identity, IpAddress ipAddress, Instant at) {
        register(identity);
        this.sightings.put(identity.uuid(), new Sighting(identity, ipAddress, at));
    }

    @Override
    public Mono<UserIdentity> resolveByLogin(String login) {
        this.resolveCalls.incrementAndGet();

        return Mono.justOrEmpty(this.identities.get(login.toLowerCase(Locale.ROOT)));
    }

    @Override
    public Mono<Void> remember(UserIdentity identity, Optional<IpAddress> ipAddress) {
        register(identity);
        ipAddress.ifPresent(address -> this.sightings.put(identity.uuid(), new Sighting(
                identity, address, this.clock.instant()
        )));

        return Mono.empty();
    }

    @Override
    public Flux<Alt> findAlts(UUID playerUuid, int limit) {
        Sighting target = this.sightings.get(playerUuid);

        if (target == null) {
            return Flux.empty();
        }

        return Flux.fromIterable(this.sightings.values())
                .filter(sighting -> !sighting.identity().uuid().equals(playerUuid))
                .filter(sighting -> sighting.ipAddress().equals(target.ipAddress()))
                .sort(Comparator.comparing(Sighting::at).reversed())
                .take(limit)
                .map(sighting -> new Alt(sighting.identity().uuid(), sighting.identity().login(), sighting.at(), true));
    }

    private record Sighting(UserIdentity identity, IpAddress ipAddress, Instant at) {
    }

}
