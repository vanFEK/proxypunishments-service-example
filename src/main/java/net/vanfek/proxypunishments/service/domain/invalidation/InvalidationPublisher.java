package net.vanfek.proxypunishments.service.domain.invalidation;

/** Implementations swallow their own transport failures: a punishment must never fail because Redis did. */
public interface InvalidationPublisher {

    void publish(InvalidationEvent event);

}
