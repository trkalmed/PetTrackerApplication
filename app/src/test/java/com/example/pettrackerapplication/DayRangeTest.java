package com.example.pettrackerapplication;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;

/**
 * "Today" is the quiet source of date bugs: a meal at 23:30 must belong to the
 * day the owner just had, not to tomorrow because the device sits east of UTC.
 * These run without a device, which is the point of keeping the logic here.
 */
public class DayRangeTest {

    private static final TimeZone BUDAPEST = TimeZone.getTimeZone("Europe/Budapest");
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    private static long at(TimeZone zone, int y, int m, int d, int hh, int mm) {
        Calendar c = new GregorianCalendar(zone);
        c.clear();
        c.set(y, m - 1, d, hh, mm, 0);
        return c.getTimeInMillis();
    }

    @Test
    public void windowStartsAtLocalMidnight() {
        long noon = at(BUDAPEST, 2026, 3, 14, 12, 0);

        DayRange range = DayRange.dayContaining(noon, BUDAPEST);

        assertEquals(at(BUDAPEST, 2026, 3, 14, 0, 0), range.startMillis);
        assertEquals(at(BUDAPEST, 2026, 3, 15, 0, 0), range.endMillis);
    }

    @Test
    public void lateEveningBelongsToTheDayJustHad() {
        // The case that breaks when a UTC day is used east of Greenwich: 23:30
        // local is already tomorrow in UTC.
        long lateEvening = at(BUDAPEST, 2026, 3, 14, 23, 30);

        DayRange range = DayRange.dayContaining(lateEvening, BUDAPEST);

        assertTrue(range.contains(lateEvening));
        assertEquals(at(BUDAPEST, 2026, 3, 14, 0, 0), range.startMillis);
    }

    @Test
    public void windowIsHalfOpen() {
        DayRange range = DayRange.dayContaining(at(UTC, 2026, 5, 2, 9, 0), UTC);

        // Midnight belongs to its own day, never to both.
        assertTrue(range.contains(range.startMillis));
        assertFalse("end must be exclusive, or a meal counts twice",
                range.contains(range.endMillis));
    }

    @Test
    public void consecutiveDaysDoNotOverlapOrGap() {
        DayRange first = DayRange.dayContaining(at(BUDAPEST, 2026, 3, 14, 8, 0), BUDAPEST);
        DayRange second = DayRange.dayContaining(at(BUDAPEST, 2026, 3, 15, 8, 0), BUDAPEST);

        assertEquals(first.endMillis, second.startMillis);
    }

    @Test
    public void springForwardDayIsTwentyThreeHours() {
        // Europe/Budapest loses an hour on 2026-03-29. Adding 24h instead of one
        // calendar day would put the boundary in the wrong place.
        DayRange range = DayRange.dayContaining(at(BUDAPEST, 2026, 3, 29, 12, 0), BUDAPEST);

        assertEquals(23 * 60 * 60 * 1000L, range.lengthMillis());
    }

    @Test
    public void autumnBackDayIsTwentyFiveHours() {
        DayRange range = DayRange.dayContaining(at(BUDAPEST, 2026, 10, 25, 12, 0), BUDAPEST);

        assertEquals(25 * 60 * 60 * 1000L, range.lengthMillis());
    }

    @Test
    public void ordinaryDayIsTwentyFourHours() {
        DayRange range = DayRange.dayContaining(at(UTC, 2026, 6, 10, 3, 0), UTC);

        assertEquals(24 * 60 * 60 * 1000L, range.lengthMillis());
    }

    @Test
    public void todayContainsNow() {
        assertTrue(DayRange.today().contains(System.currentTimeMillis()));
    }
}
