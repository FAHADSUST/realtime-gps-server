package com.rls.gps.ping.publish;

import java.util.List;

import com.rls.gps.common.error.ApiExceptions;
import com.rls.gps.messaging.LocationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies the configured {@link OverflowPolicy} when offering fixes to the buffer.
 *
 * <p>Separate from the buffer (which stays a dumb bounded queue) and from the service (which would
 * otherwise need Redis to test a policy decision).
 */
public class LocationAdmission {

    private static final Logger log = LoggerFactory.getLogger(LocationAdmission.class);

    private final LocationBuffer buffer;
    private final OverflowPolicy policy;

    public LocationAdmission(LocationBuffer buffer, OverflowPolicy policy) {
        this.buffer = buffer;
        this.policy = policy;
    }

    /**
     * @return how many fixes could not be buffered
     * @throws com.rls.gps.common.error.ApiException 429 under {@link OverflowPolicy#REJECT}
     */
    public int admit(List<LocationMessage> messages) {
        int dropped = 0;

        for (LocationMessage message : messages) {
            if (buffer.offer(message)) {
                continue;
            }

            switch (policy) {
                case DROP_NEWEST -> dropped++;
                case DROP_OLDEST -> {
                    buffer.discardOldest();
                    if (!buffer.offer(message)) {
                        // Another thread refilled the slot; the fix is lost either way.
                        dropped++;
                    }
                }
                case REJECT -> {
                    log.warn("location_buffer_full rejecting request bufferSize={}", buffer.size());
                    throw ApiExceptions.tooManyRequests("location_buffer_full",
                            "The service is not keeping up; retry shortly");
                }
            }
        }

        return dropped;
    }

    public OverflowPolicy policy() {
        return policy;
    }
}
