package application;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Which saved calculations the ledger shows, and in what order. Pure logic with no JavaFX,
 * so it can be tested on its own.
 *
 * A time window may run past midnight: 22:00 to 06:00 keeps the late evening and the early morning.
 */
public final class HistoryFilter {

    public static final String PERIOD_ALL = "All time";
    public static final String PERIOD_TODAY = "Today";
    public static final String PERIOD_LAST_7 = "Last 7 days";
    public static final String PERIOD_LAST_30 = "Last 30 days";
    public static final String PERIOD_THIS_MONTH = "This month";
    public static final String PERIOD_CUSTOM = "Custom range";

    public static final String SORT_NEWEST = "Newest first";
    public static final String SORT_OLDEST = "Oldest first";

    private static final DateTimeFormatter TYPED_TIME = DateTimeFormatter.ofPattern("H:mm");

    /** No filtering, newest first. */
    public static final HistoryFilter ALL = new HistoryFilter(null, "", null, null, null, null, false);

    /** The calculator to show, or null for every calculator. */
    final String calculator;
    /** Lower-case text to find anywhere in the saved line, or "" for any. */
    final String text;
    /** First and last day to show, inclusive. Null means no limit. */
    final LocalDate from;
    final LocalDate to;
    /** Time of day window, inclusive. Null means no limit. */
    final LocalTime timeFrom;
    final LocalTime timeTo;
    /** Oldest entries first, rather than newest first. */
    final boolean oldestFirst;

    public HistoryFilter(String calculator, String text, LocalDate from, LocalDate to,
                         LocalTime timeFrom, LocalTime timeTo, boolean oldestFirst) {
        this.calculator = calculator;
        this.text = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        this.from = from;
        this.to = to;
        this.timeFrom = timeFrom;
        this.timeTo = timeTo;
        this.oldestFirst = oldestFirst;
    }

    /** True when the entry passes every filter that is set. */
    public boolean matches(HistoryRecord record) {
        if (calculator != null && !calculator.equals(record.calculator)) {
            return false;
        }
        // Matches the saved line, or the line as it is shown (so "1 234,56" finds 1234.56)
        String shown = Money.text(record.raw).replace(' ', ' ').toLowerCase(Locale.ROOT);
        if (!text.isEmpty() && !record.raw.toLowerCase(Locale.ROOT).contains(text) && !shown.contains(text)) {
            return false;
        }
        boolean dated = from != null || to != null || timeFrom != null || timeTo != null;
        if (dated && record.date == null) {
            return false; // a line we could not read has no date to compare
        }
        if (from != null && record.date.isBefore(from)) {
            return false;
        }
        if (to != null && record.date.isAfter(to)) {
            return false;
        }
        if (timeFrom != null || timeTo != null) {
            LocalTime time = timeOf(record);
            if (time == null || !inWindow(time)) {
                return false;
            }
        }
        return true;
    }

    private boolean inWindow(LocalTime time) {
        LocalTime start = timeFrom == null ? LocalTime.MIN : timeFrom;
        LocalTime end = timeTo == null ? LocalTime.MAX : timeTo;
        if (!start.isAfter(end)) {
            return !time.isBefore(start) && !time.isAfter(end);
        }
        // The window runs past midnight, e.g. 22:00 to 06:00
        return !time.isBefore(start) || !time.isAfter(end);
    }

    private static LocalTime timeOf(HistoryRecord record) {
        try {
            return LocalTime.parse(record.time);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** Reads a time typed as 8:30 or 08:30. Blank means no limit. Throws if the text is not a time. */
    public static LocalTime parseTime(String typed) {
        if (typed == null || typed.isBlank()) {
            return null;
        }
        return LocalTime.parse(typed.trim(), TYPED_TIME);
    }

    /**
     * The first and last day of a period preset, inclusive. A null first element means no start limit.
     * Returns null for a custom range, which the user sets by hand.
     */
    public static LocalDate[] range(String period, LocalDate today) {
        if (PERIOD_ALL.equals(period)) {
            return new LocalDate[] {null, null};
        }
        if (PERIOD_TODAY.equals(period)) {
            return new LocalDate[] {today, today};
        }
        if (PERIOD_LAST_7.equals(period)) {
            return new LocalDate[] {today.minusDays(6), today};
        }
        if (PERIOD_LAST_30.equals(period)) {
            return new LocalDate[] {today.minusDays(29), today};
        }
        if (PERIOD_THIS_MONTH.equals(period)) {
            return new LocalDate[] {today.withDayOfMonth(1), today.withDayOfMonth(today.lengthOfMonth())};
        }
        return null;
    }
}
