package com.rls.gps.history.consume;

import com.rls.gps.messaging.LocationMessage;

/**
 * Decides whether a fix can be stored at all.
 *
 * <p>The distinction this class exists to draw: a malformed fix is <em>poison</em> - no amount of
 * retrying will make a null user id storable - while a database being down is <em>transient</em>.
 * Treating them the same way means either retrying a bug forever or dead-lettering a batch because
 * MySQL blinked.
 */
final class FixValidation {

    private FixValidation() {
    }

    /**
     * @return the reason this fix cannot be stored, or {@code null} when it can
     */
    static String rejectionReason(LocationMessage fix) {
        if (fix == null) {
            return "null fix";
        }
        if (isBlank(fix.companyId()) || isBlank(fix.userId())) {
            return "missing company or user";
        }
        if (fix.recordedAt() == null || fix.receivedAt() == null) {
            return "missing timestamp";
        }
        if (!isFinite(fix.latitude()) || !isFinite(fix.longitude())) {
            return "coordinate is not a number";
        }
        if (fix.latitude() < -90 || fix.latitude() > 90 || fix.longitude() < -180 || fix.longitude() > 180) {
            return "coordinate out of range";
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }
}
