package com.rls.gps.ping.publish;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.rls.gps.messaging.LocationMessage;
import com.rls.gps.ping.metrics.PingMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Drains {@link LocationBuffer} into the sink, forever, on one dedicated thread.
 *
 * <p>A dedicated thread rather than a scheduled task: the buffer's own {@code takeBatch} already
 * blocks until there is work or the interval expires, so this publishes the moment a full batch
 * exists instead of waiting for the next tick. One thread also means batches are published in order
 * and the broker sees a single producer.
 */
public class LocationBufferFlusher implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(LocationBufferFlusher.class);

    private final LocationBuffer buffer;
    private final LocationBatchSink sink;
    private final PingMetrics metrics;
    private final long shutdownTimeoutMillis;

    private volatile boolean running;
    private Thread worker;
    private final CountDownLatch stopped = new CountDownLatch(1);

    public LocationBufferFlusher(LocationBuffer buffer, LocationBatchSink sink, PingMetrics metrics,
                                 long shutdownTimeoutMillis) {
        this.buffer = buffer;
        this.sink = sink;
        this.metrics = metrics;
        this.shutdownTimeoutMillis = shutdownTimeoutMillis;
    }

    @Override
    public void start() {
        running = true;
        worker = new Thread(this::flushLoop, "location-flusher");
        worker.setDaemon(true);
        worker.start();
        log.info("location_flusher_started");
    }

    private void flushLoop() {
        try {
            while (running) {
                List<LocationMessage> batch = buffer.takeBatch();
                if (!batch.isEmpty()) {
                    publish(batch);
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } finally {
            stopped.countDown();
        }
    }

    /**
     * A failing sink must not kill the flusher.
     *
     * <p>If the loop died on a broker hiccup the service would keep accepting locations and quietly
     * never publish any of them again - the worst possible failure mode, because every health check
     * still passes.
     */
    private void publish(List<LocationMessage> batch) {
        try {
            sink.send(batch);
            metrics.batchPublished();
        } catch (RuntimeException ex) {
            metrics.batchFailed();
            log.error("location_batch_publish_failed size={} - batch dropped", batch.size(), ex);
        }
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }
        running = false;

        // Let the loop finish the batch it is on, then take whatever is left in one go. Losing the
        // buffer on every deploy would be a needless hole in the history.
        try {
            if (!stopped.await(shutdownTimeoutMillis, TimeUnit.MILLISECONDS)) {
                log.warn("location_flusher_did_not_stop_in_time");
                worker.interrupt();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }

        List<List<LocationMessage>> remaining = buffer.drainRemaining();
        int drained = remaining.stream().mapToInt(List::size).sum();
        remaining.forEach(this::publish);

        log.info("location_flusher_stopped drained={}", drained);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * Stops before the web server does, so requests in flight can still add to a buffer that is
     * still being drained.
     */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }
}
