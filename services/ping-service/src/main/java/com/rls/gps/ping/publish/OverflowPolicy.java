package com.rls.gps.ping.publish;

/** What to do with a fix when the buffer is full - that is, when the broker cannot keep up. */
public enum OverflowPolicy {

    /**
     * Take the request, discard what does not fit, and say so in the response.
     *
     * <p>The default. The service stays responsive, the client learns its history has a gap, and
     * nothing that was already accepted is thrown away.
     */
    DROP_NEWEST,

    /**
     * Make room by discarding the oldest queued fix.
     *
     * <p>Favours recent tracks over complete ones. Not the default because those older fixes were
     * already answered with a 202 - discarding them retracts a promise the platform made.
     */
    DROP_OLDEST,

    /**
     * Refuse the request with 429 so the client keeps the data and retries.
     *
     * <p>The best outcome when devices buffer and retry, and the worst when they do not.
     */
    REJECT
}
