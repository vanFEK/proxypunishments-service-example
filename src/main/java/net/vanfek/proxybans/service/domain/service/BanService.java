package net.vanfek.proxybans.service.domain.service;

import com.github.benmanes.caffeine.cache.AsyncCache;
import net.vanfek.proxybans.service.domain.model.ActiveBan;
import net.vanfek.proxybans.service.domain.model.IssueBanCommand;
import net.vanfek.proxybans.service.domain.model.LiftBanCommand;
import net.vanfek.proxybans.service.domain.model.PunishmentLogEntry;
import net.vanfek.proxybans.service.domain.model.UserIdentity;
import net.vanfek.proxybans.service.domain.port.BanRepository;
import net.vanfek.proxybans.service.domain.port.UserResolver;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Application service: caching and cache invalidation around the storage ports.
 *
 * <p>Note that a negative answer ("this player is not banned") is cached as deliberately as a
 * positive one. Most logins are from players who are not banned, so caching only the bans would
 * leave the hot path hitting the database every time.
 */
@Service
public class BanService {

    private final BanRepository bans;
    private final UserResolver users;
    private final AsyncCache<UUID, Optional<ActiveBan>> activeBanCache;
    private final AsyncCache<String, Optional<UserIdentity>> lookupCache;

    public BanService(
            BanRepository bans,
            UserResolver users,
            AsyncCache<UUID, Optional<ActiveBan>> activeBanCache,
            AsyncCache<String, Optional<UserIdentity>> lookupCache
    ) {
        this.bans = bans;
        this.users = users;
        this.activeBanCache = activeBanCache;
        this.lookupCache = lookupCache;
    }

    public Mono<Optional<ActiveBan>> activeBanFor(UUID playerUuid) {
        return Mono.fromFuture(this.activeBanCache.get(playerUuid, (key, executor) -> this.bans.findActive(key)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .toFuture()));
    }

    public Mono<ActiveBan> issue(IssueBanCommand command) {
        return this.bans.issue(command)
                .doOnSuccess(ban -> this.activeBanCache.synchronous().invalidate(command.playerUuid()));
    }

    public Mono<Void> lift(LiftBanCommand command) {
        return this.bans.lift(command)
                .doOnSuccess(unused -> this.activeBanCache.synchronous().invalidate(command.playerUuid()));
    }

    public Flux<PunishmentLogEntry> history(UUID playerUuid, int limit, int offset) {
        return this.bans.history(playerUuid, limit, offset);
    }

    public Flux<ActiveBan> listActive(int limit, int offset) {
        return this.bans.listActive(limit, offset);
    }

    public Mono<Optional<UserIdentity>> resolveByLogin(String login) {
        String key = login.toLowerCase(Locale.ROOT);

        return Mono.fromFuture(this.lookupCache.get(key, (cacheKey, executor) -> this.users.resolveByLogin(login)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .toFuture()));
    }

    public Mono<Void> remember(UserIdentity identity) {
        return this.users.remember(identity)
                .doOnSuccess(unused -> this.lookupCache.synchronous().invalidate(identity.loginKey()));
    }

}
