package com.rls.gps.ping.publish;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import com.rls.gps.messaging.LocationMessage;

/**
 * The in-memory staging area between an HTTP request and the broker.
 *
 * <p>The spec is explicit that ping "stores the location data on memory and after a certain time
 * pushes them to the RabbitMQ as a bulk". That is what makes the reported throughput possible: a
 * request returns as soon as the fix is in memory, and the broker sees one publish per batch instead
 * of one per fix.
 *
 * <p>The queue is <strong>bounded</strong>. An unbounded queue does not mean "never lose data" - it
 * means that when the broker is slow the service keeps accepting work until the heap is gone, and
 * then loses everything at once, including the requests it was serving. A bounded queue loses the
 * overflow and keeps running.
 */
public class LocationBuffer {

    private final BlockingQueue<LocationMessage> queue;
    private final int maxBatchSize;
    private final long flushIntervalMillis;

    public LocationBuffer(int capacity, int maxBatchSize, Duration flushInterval) {
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.maxBatchSize = maxBatchSize;
        this.flushIntervalMillis = flushInterval.toMillis();
    }

    /**
     * @return false when the buffer is full - the caller decides what that means
     */
    public boolean offer(LocationMessage message) {
        return queue.offer(message);
    }

    /**
     * Waits for work and returns as much of it as one batch may carry.
     *
     * <p>Blocks up to the flush interval for the first message, then takes whatever else is already
     * queued. So a busy service publishes full batches immediately, and a quiet one still publishes
     * a single fix within the interval instead of holding it until the next one arrives.
     *
     * @return a batch, or an empty list when the interval passed with nothing to send
     */
    public List<LocationMessage> takeBatch() throws InterruptedException {
        LocationMessage first = queue.poll(flushIntervalMillis, TimeUnit.MILLISECONDS);
        if (first == null) {
            return List.of();
        }

        List<LocationMessage> batch = new ArrayList<>(maxBatchSize);
        batch.add(first);
        queue.drainTo(batch, maxBatchSize - 1);
        return batch;
    }

    /** Everything still queued, in batches - used to empty the buffer during shutdown. */
    public List<List<LocationMessage>> drainRemaining() {
        List<List<LocationMessage>> batches = new ArrayList<>();
        List<LocationMessage> drained = new ArrayList<>(queue.size());
        queue.drainTo(drained);

        for (int index = 0; index < drained.size(); index += maxBatchSize) {
            batches.add(List.copyOf(drained.subList(index, Math.min(index + maxBatchSize, drained.size()))));
        }
        return batches;
    }

    public int size() {
        return queue.size();
    }

    public int remainingCapacity() {
        return queue.remainingCapacity();
    }
}
