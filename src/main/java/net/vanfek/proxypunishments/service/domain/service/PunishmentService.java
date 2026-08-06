package net.vanfek.proxypunishments.service.domain.service;

import com.github.benmanes.caffeine.cache.AsyncCache;
import net.vanfek.proxypunishments.service.domain.invalidation.InvalidationEvent;
import net.vanfek.proxypunishments.service.domain.invalidation.InvalidationPublisher;
import net.vanfek.proxypunishments.service.domain.metrics.PunishmentMetrics;
import net.vanfek.proxypunishments.service.domain.model.ActivePunishment;
import net.vanfek.proxypunishments.service.domain.model.ActivePunishmentKey;
import net.vanfek.proxypunishments.service.domain.model.IssuePunishmentCommand;
import net.vanfek.proxypunishments.service.domain.model.IssueWarningCommand;
import net.vanfek.proxypunishments.service.domain.model.LiftPunishmentCommand;
import net.vanfek.proxypunishments.service.domain.model.PunishmentKind;
import net.vanfek.proxypunishments.service.domain.model.PunishmentLogEntry;
import net.vanfek.proxypunishments.service.domain.model.UserIdentity;
import net.vanfek.proxypunishments.service.domain.model.ip.Alt;
import net.vanfek.proxypunishments.service.domain.model.ip.IpAddress;
import net.vanfek.proxypunishments.service.domain.model.ip.LiftIpBanCommand;
import net.vanfek.proxypunishments.service.domain.notify.PunishmentAnnouncer;
import net.vanfek.proxypunishments.service.domain.port.PunishmentRepository;
import net.vanfek.proxypunishments.service.domain.port.UserResolver;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class PunishmentService {

    private final PunishmentRepository punishments;
    private final UserResolver users;
    private final AsyncCache<ActivePunishmentKey, Optional<ActivePunishment>> activePunishmentCache;
    private final AsyncCache<IpAddress, Optional<ActivePunishment>> ipBanCache;
    private final AsyncCache<String, Optional<UserIdentity>> lookupCache;
    private final InvalidationPublisher invalidations;
    private final PunishmentMetrics metrics;
    private final PunishmentAnnouncer announcer;

    public PunishmentService(
            PunishmentRepository punishments, UserResolver users,
            AsyncCache<ActivePunishmentKey, Optional<ActivePunishment>> activePunishmentCache,
            AsyncCache<IpAddress, Optional<ActivePunishment>> ipBanCache,
            AsyncCache<String, Optional<UserIdentity>> lookupCache,
            InvalidationPublisher invalidations, PunishmentMetrics metrics,
            PunishmentAnnouncer announcer
    ) {
        this.punishments = punishments;
        this.users = users;
        this.activePunishmentCache = activePunishmentCache;
        this.ipBanCache = ipBanCache;
        this.lookupCache = lookupCache;
        this.invalidations = invalidations;
        this.metrics = metrics;
        this.announcer = announcer;
    }

    public Mono<Optional<ActivePunishment>> activePunishmentFor(UUID playerUuid, PunishmentKind kind) {
        ActivePunishmentKey key = new ActivePunishmentKey(playerUuid, kind);
        AtomicBoolean loaded = new AtomicBoolean();

        CompletableFuture<Optional<ActivePunishment>> answer = this.activePunishmentCache.get(key, (cacheKey, executor) -> {
            loaded.set(true);

            return this.metrics.timed("findActive", this.punishments
                            .findActive(cacheKey.playerUuid(), cacheKey.kind())
                            .map(Optional::of)
                            .defaultIfEmpty(Optional.empty()))
                    .toFuture();
        });

        this.metrics.cacheLookup(PunishmentMetrics.ACTIVE_PUNISHMENT_CACHE, !loaded.get());

        return Mono.fromFuture(answer);
    }

    public Mono<Optional<ActivePunishment>> activeIpBanFor(IpAddress ipAddress) {
        AtomicBoolean loaded = new AtomicBoolean();

        CompletableFuture<Optional<ActivePunishment>> answer = this.ipBanCache.get(ipAddress, (cacheKey, executor) -> {
            loaded.set(true);

            return this.metrics.timed("findActiveByIp", this.punishments
                            .findActiveByIp(cacheKey)
                            .map(Optional::of)
                            .defaultIfEmpty(Optional.empty()))
                    .toFuture();
        });

        this.metrics.cacheLookup(PunishmentMetrics.IP_BAN_CACHE, !loaded.get());

        return Mono.fromFuture(answer);
    }

    public Mono<ActivePunishment> issue(IssuePunishmentCommand command) {
        return this.metrics.timed("issue", this.punishments.issue(command))
                .doOnSuccess(punishment -> onIssued(command, punishment));
    }

    public Mono<Void> lift(LiftPunishmentCommand command) {
        return this.metrics.timed("lift", this.punishments.lift(command))
                .doOnSuccess(unused -> onLifted(command));
    }

    public Mono<Void> liftIpBan(LiftIpBanCommand command) {
        return this.metrics.timed("liftIpBan", this.punishments.liftIpBan(command))
                .doOnSuccess(unused -> onIpBanLifted(command));
    }

    public Mono<PunishmentLogEntry> warn(IssueWarningCommand command) {
        return this.metrics.timed("warn", this.punishments.warn(command));
    }

    public Flux<PunishmentLogEntry> history(UUID playerUuid, int limit, int offset) {
        return this.metrics.timed("history", this.punishments.history(playerUuid, limit, offset));
    }

    public Flux<ActivePunishment> listActive(PunishmentKind kind, int limit, int offset) {
        return this.metrics.timed("listActive", this.punishments.listActive(kind, limit, offset));
    }

    public Flux<Alt> alts(UUID playerUuid, int limit) {
        return this.metrics.timed("alts", this.users.findAlts(playerUuid, limit));
    }

    public Mono<Optional<UserIdentity>> resolveByLogin(String login) {
        String key = login.toLowerCase(Locale.ROOT);
        AtomicBoolean loaded = new AtomicBoolean();

        CompletableFuture<Optional<UserIdentity>> answer = this.lookupCache.get(key, (cacheKey, executor) -> {
            loaded.set(true);

            return this.metrics.timed("resolveByLogin", this.users.resolveByLogin(login)
                            .map(Optional::of)
                            .defaultIfEmpty(Optional.empty()))
                    .toFuture();
        });

        this.metrics.cacheLookup(PunishmentMetrics.LOOKUP_CACHE, !loaded.get());

        return Mono.fromFuture(answer);
    }

    public Mono<Void> remember(UserIdentity identity, Optional<IpAddress> ipAddress) {
        return this.metrics.timed("remember", this.users.remember(identity, ipAddress))
                .doOnSuccess(unused -> this.lookupCache.synchronous().invalidate(identity.loginKey()));
    }

    public void applyInvalidation(InvalidationEvent event) {
        if (event.targetsAddress()) {
            Optional<IpAddress> address = IpAddress.parse(event.target());

            if (address.isEmpty()) {
                return;
            }

            this.ipBanCache.synchronous().invalidate(address.get());
            this.metrics.invalidationReceived();

            return;
        }

        Optional<UUID> playerUuid = playerUuid(event.target());

        if (playerUuid.isEmpty()) {
            return;
        }

        this.activePunishmentCache.synchronous().invalidate(new ActivePunishmentKey(playerUuid.get(), event.kind()));
        this.metrics.invalidationReceived();
    }

    private void onIssued(IssuePunishmentCommand command, ActivePunishment punishment) {
        this.activePunishmentCache.synchronous().invalidate(command.key());
        command.ipAddress().ifPresent(address -> this.ipBanCache.synchronous().invalidate(address));

        if (punishment == null) {
            return;
        }

        this.metrics.punishmentIssued(command.kind());
        this.invalidations.publish(invalidationFor(command));
        this.announcer.announceIssued(punishment);
    }

    private void onLifted(LiftPunishmentCommand command) {
        this.activePunishmentCache.synchronous().invalidate(command.key());

        this.metrics.punishmentLifted(command.kind());
        this.invalidations.publish(InvalidationEvent.forPlayer(command.playerUuid(), command.kind()));
        this.announcer.announceLifted(command);
    }

    private void onIpBanLifted(LiftIpBanCommand command) {
        this.ipBanCache.synchronous().invalidate(command.ipAddress());

        this.metrics.punishmentLifted(PunishmentKind.IP_BAN);
        this.invalidations.publish(InvalidationEvent.forAddress(command.ipAddress()));
        this.announcer.announceIpBanLifted(command);
    }

    private static InvalidationEvent invalidationFor(IssuePunishmentCommand command) {
        return command.ipAddress()
                .filter(unused -> command.kind().targetsAddress())
                .map(InvalidationEvent::forAddress)
                .orElseGet(() -> InvalidationEvent.forPlayer(command.playerUuid(), command.kind()));
    }

    private static Optional<UUID> playerUuid(String target) {
        try {
            return Optional.of(UUID.fromString(target));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

}
