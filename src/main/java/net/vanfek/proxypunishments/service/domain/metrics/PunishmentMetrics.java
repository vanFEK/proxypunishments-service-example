package net.vanfek.proxypunishments.service.domain.metrics;

import net.vanfek.proxypunishments.service.domain.model.PunishmentKind;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface PunishmentMetrics {

    String ACTIVE_PUNISHMENT_CACHE = "active-punishment";
    String IP_BAN_CACHE = "ip-ban";
    String LOOKUP_CACHE = "lookup";

    String WEBHOOK_SENT = "sent";
    String WEBHOOK_FAILED = "failed";
    String WEBHOOK_RATE_LIMITED = "rate-limited";
    String WEBHOOK_SUPPRESSED = "suppressed";

    void punishmentIssued(PunishmentKind kind);

    void punishmentLifted(PunishmentKind kind);

    void cacheLookup(String cache, boolean hit);

    void invalidationPublished();

    void invalidationReceived();

    void webhookDelivery(String outcome);

    <T> Mono<T> timed(String operation, Mono<T> call);

    <T> Flux<T> timed(String operation, Flux<T> call);

}
