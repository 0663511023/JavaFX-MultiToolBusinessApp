package application;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One line of the calculation history, split into its parts.
 * Pure logic with no JavaFX, so it can be tested on its own.
 *
 * Stored format: [Calculator] (yyyy-MM-dd HH:mm:ss)  expression -> result
 */
public final class HistoryRecord {

    private static final Pattern LINE = Pattern.compile(
            "^\\[(.*?)\\] \\((\\d{4}-\\d{2}-\\d{2}) (\\d{2}:\\d{2}:\\d{2})\\)\\s+(.*?) -> (.*)$");
    private static final DateTimeFormatter DAY_LABEL =
            DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH);

    public final String raw;
    public final String calculator;
    /** Null when the saved line could not be read. */
    public final LocalDate date;
    public final String time;
    public final String expression;
    public final String result;

    private HistoryRecord(String raw, String calculator, LocalDate date, String time,
                          String expression, String result) {
        this.raw = raw;
        this.calculator = calculator;
        this.date = date;
        this.time = time;
        this.expression = expression;
        this.result = result;
    }

    public static HistoryRecord parse(String raw) {
        Matcher m = LINE.matcher(raw);
        if (m.matches()) {
            try {
                return new HistoryRecord(raw, m.group(1), LocalDate.parse(m.group(2)),
                        m.group(3), m.group(4), m.group(5));
            } catch (DateTimeParseException ignored) {
                // fall through to the unreadable-line case
            }
        }
        return new HistoryRecord(raw, "", null, "", raw, "");
    }

    /** Heading for a day group, e.g. "Wednesday, 11 November 2026". */
    public String dayLabel() {
        return date == null ? "Unknown date" : date.format(DAY_LABEL);
    }

    /** Date as yyyy-MM-dd, for exports. Empty when unknown. */
    public String dateText() {
        return date == null ? "" : date.toString();
    }

    /** One line as shown in the list, under its day heading. */
    public String displayLine() {
        if (date == null) {
            return raw;
        }
        return time + "    " + calculator + "    " + Money.text(expression) + "  ->  " + Money.text(result);
    }
}
