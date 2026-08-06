package net.vanfek.proxybans.service.domain.service;

import com.github.benmanes.caffeine.cache.AsyncCache;
import net.vanfek.proxybans.service.domain.model.ActivePunishment;
import net.vanfek.proxybans.service.domain.model.ActivePunishmentKey;
import net.vanfek.proxybans.service.domain.model.IssuePunishmentCommand;
import net.vanfek.proxybans.service.domain.model.IssueWarningCommand;
import net.vanfek.proxybans.service.domain.model.LiftPunishmentCommand;
import net.vanfek.proxybans.service.domain.model.PunishmentKind;
import net.vanfek.proxybans.service.domain.model.PunishmentLogEntry;
import net.vanfek.proxybans.service.domain.model.UserIdentity;
import net.vanfek.proxybans.service.domain.port.PunishmentRepository;
import net.vanfek.proxybans.service.domain.port.UserResolver;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class PunishmentService {

    private final PunishmentRepository punishments;
    private final UserResolver users;
    private final AsyncCache<ActivePunishmentKey, Optional<ActivePunishment>> activePunishmentCache;
    private final AsyncCache<String, Optional<UserIdentity>> lookupCache;

    public PunishmentService(
            PunishmentRepository punishments, UserResolver users,
            AsyncCache<ActivePunishmentKey, Optional<ActivePunishment>> activePunishmentCache,
            AsyncCache<String, Optional<UserIdentity>> lookupCache
    ) {
        this.punishments = punishments;
        this.users = users;
        this.activePunishmentCache = activePunishmentCache;
        this.lookupCache = lookupCache;
    }

    public Mono<Optional<ActivePunishment>> activePunishmentFor(UUID playerUuid, PunishmentKind kind) {
        ActivePunishmentKey key = new ActivePunishmentKey(playerUuid, kind);

        return Mono.fromFuture(this.activePunishmentCache.get(key, (cacheKey, executor) -> this.punishments
                .findActive(cacheKey.playerUuid(), cacheKey.kind())
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .toFuture()));
    }

    public Mono<ActivePunishment> issue(IssuePunishmentCommand command) {
        return this.punishments.issue(command)
                .doOnSuccess(punishment -> this.activePunishmentCache.synchronous().invalidate(command.key()));
    }

    public Mono<Void> lift(LiftPunishmentCommand command) {
        return this.punishments.lift(command)
                .doOnSuccess(unused -> this.activePunishmentCache.synchronous().invalidate(command.key()));
    }

    public Mono<PunishmentLogEntry> warn(IssueWarningCommand command) {
        return this.punishments.warn(command);
    }

    public Flux<PunishmentLogEntry> history(UUID playerUuid, int limit, int offset) {
        return this.punishments.history(playerUuid, limit, offset);
    }

    public Flux<ActivePunishment> listActive(PunishmentKind kind, int limit, int offset) {
        return this.punishments.listActive(kind, limit, offset);
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
