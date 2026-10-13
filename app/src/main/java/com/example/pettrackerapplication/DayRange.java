package com.example.pettrackerapplication;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/** A local day window [start, end) in epoch millis. */
public final class DayRange {

    public final long startMillis;
    public final long endMillis;

    private DayRange(long startMillis, long endMillis) {
        this.startMillis = startMillis;
        this.endMillis = endMillis;
    }

    public static DayRange dayContaining(long atMillis, TimeZone zone) {
        Calendar cal = Calendar.getInstance(zone, Locale.getDefault());
        cal.setTimeInMillis(atMillis);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long start = cal.getTimeInMillis();

        // Add a calendar day, not 24 h, so daylight saving changes stay correct.
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
