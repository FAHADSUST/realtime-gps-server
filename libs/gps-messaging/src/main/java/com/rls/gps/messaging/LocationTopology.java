package com.rls.gps.messaging;

/**
 * Names of the exchange, queue and bindings the location pipeline runs on.
 *
 * <p>Topology lives in this shared module rather than in Compose or in each service, so the
 * publisher and the consumer declare byte-identical definitions. Split across two places, a
 * mismatched queue argument does not show up as a config diff - it shows up at runtime as
 * {@code PRECONDITION_FAILED} on whichever service starts second.
 */
public final class LocationTopology {

    /** Where the ping service publishes. */
    public static final String EXCHANGE = "gps.location";

    /** Routing key for a batch of locations. */
    public static final String ROUTING_KEY = "location.batch";

    /** The History service is the only consumer, per the specification. */
    public static final String QUEUE = "gps.location.history";

    /** Batches that could not be processed end up here rather than being redelivered forever. */
    public static final String DEAD_LETTER_EXCHANGE = "gps.location.dlx";
    public static final String DEAD_LETTER_QUEUE = "gps.location.history.dlq";
    public static final String DEAD_LETTER_ROUTING_KEY = "location.batch.dead";

    private LocationTopology() {
    }
}
