package net.vanfek.proxypunishments.service.adapter.redis;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The transport seam: everything above it is testable without a Redis anywhere in sight. */
public interface InvalidationChannel {

    Mono<Long> send(String payload);

    Flux<String> receive();

}
