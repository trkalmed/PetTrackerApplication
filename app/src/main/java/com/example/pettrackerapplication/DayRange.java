package com.example.pettrackerapplication;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * A half-open day window, [start, end), in epoch millis.
 *
 * "Today" is a local-time idea, not a UTC one: a meal at 23:30 belongs to the
 * day the owner just had, not to tomorrow because the device happens to be east
 * of UTC. Every dashboard query goes through here so that judgement is made in
 * one place and can be tested without a device.
 */
public final class DayRange {

    public final long startMillis;
    public final long endMillis;

    private DayRange(long startMillis, long endMillis) {
        this.startMillis = startMillis;
        this.endMillis = endMillis;
    }

    /** The local day containing {@code atMillis}, in the given zone. */
    public static DayRange dayContaining(long atMillis, TimeZone zone) {
        Calendar cal = Calendar.getInstance(zone, Locale.getDefault());
        cal.setTimeInMillis(atMillis);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long start = cal.getTimeInMillis();

        // Add a day rather than adding 24h: that keeps the window correct across
        // a daylight-saving change, where a local day is 23 or 25 hours long.
        cal.add(Calendar.DAY_OF_MONTH, 1);
        return new DayRange(start, cal.getTimeInMillis());
    }

    public static DayRange today() {
        return dayContaining(System.currentTimeMillis(), TimeZone.getDefault());
    }

    public boolean contains(long millis) {
        return millis >= startMillis && millis < endMillis;
    }

    public long lengthMillis() {
        return endMillis - startMillis;
    }
}
