package com.rls.gps.ping.publish;

import java.util.List;

import com.rls.gps.messaging.LocationMessage;

/**
 * Where a flushed batch goes.
 *
 * <p>An interface rather than a direct call to the publisher, so the buffer can be tested at full
 * speed against a collecting stub - no broker, no container.
 */
public interface LocationBatchSink {

    void send(List<LocationMessage> batch);
}
