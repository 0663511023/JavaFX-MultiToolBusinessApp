package application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Number text for the screen and the exports, in the style chosen in Settings.
 *
 *   South African (default):  R 1 234,56     11,5%
 *   Standard:                 R 1,234.56     11.5%
 *
 * Only what is shown changes. Saved history lines keep plain dot decimals (1234.56), so they always
 * reload, and typed input is read in either style by InputParser. Pure logic with no JavaFX.
 */
public final class Money {

    /** The two styles, as they are stored in the settings file. */
    public static final String SA = "sa";
    public static final String STANDARD = "standard";

    /** A non-breaking space, so a thousands group never splits across two lines. */
    private static final String SA_GROUP = " ";

    /** A machine number such as 1234.56 or R1234.56. Dates and times (2026-11-12, 22:33) are left alone. */
    private static final Pattern MACHINE_NUMBER = Pattern.compile(
            "(?<![\\d.,:/])(?<!\\d-)(R ?)?(\\d+)(\\.\\d+)?(?!\\d|\\.\\d|[:/]\\d|-\\d|,\\d)");

    /** The placeholders used on screen: %,.2f, %.1f, %s, %d and %%. */
    private static final Pattern PLACEHOLDER = Pattern.compile("%(,?)\\.(\\d+)f|%([sd])|%%");

    private Money() {}

    /** True unless the standard style is chosen. The South African style is the default. */
    static boolean isSa() {
        return !STANDARD.equals(AppSettings.numberStyle());
    }

    // ---------------------------------------------------------------- on screen

    /** A rand amount to two decimals, such as "R 1 234,56". */
    public static String amount(BigDecimal value) {
        return "R " + number(value, 2, true);
    }

    /** A rand amount to two decimals, such as "R 1 234,56". */
    public static String amount(double value) {
        return "R " + number(toDecimal(value), 2, true);
    }

    /** A number to the given decimals, with thousands grouping. */
    public static String number(double value, int decimals) {
        return number(toDecimal(value), decimals, true);
    }

    /** A number to the given decimals, with thousands grouping. */
    public static String number(BigDecimal value, int decimals) {
        return number(value, decimals, true);
    }

    /**
     * String.format for the screen. It takes the same placeholders as before, so only the numbers change.
     * %,.2f groups thousands, %.2f does not, and %s and %% are passed through unchanged.
     */
    public static String format(String pattern, Object... args) {
        Matcher m = PLACEHOLDER.matcher(pattern);
        StringBuilder out = new StringBuilder();
        int next = 0;
        while (m.find()) {
            String text;
            if ("%%".equals(m.group())) {
                text = "%";
            } else if (m.group(2) != null) {
                text = number(toDecimal(args[next++]), Integer.parseInt(m.group(2)), !m.group(1).isEmpty());
            } else {
                text = String.valueOf(args[next++]);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(text));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * Shows machine text in the chosen style. Every plain number in it is changed, and the rest of the
     * text stays as it is: "R1234.56 @ 11.5%" becomes "R 1 234,56 @ 11,5%" in the South African style.
     */
    public static String text(String machine) {
        if (machine == null || machine.isEmpty()) {
            return "";
        }
        Matcher m = MACHINE_NUMBER.matcher(machine);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            // A non-breaking space after the R, so a line never wraps between the R and its amount
            String prefix = m.group(1) == null ? "" : "R" + SA_GROUP;
            String fraction = m.group(3);
            int decimals = fraction == null ? 0 : fraction.length() - 1;
            BigDecimal value = new BigDecimal(m.group(2) + (fraction == null ? "" : fraction));
            String shown = prefix + number(value, decimals, true);
            m.appendReplacement(out, Matcher.quoteReplacement(shown));
        }
        m.appendTail(out);
        return out.toString();
    }

    // ---------------------------------------------------------------- saved text

    /** Plain machine text for saving or for a text field: dot decimal, no grouping, no trailing zeros. */
    public static String machine(double value) {
        BigDecimal d = toDecimal(value);
        return d == null ? "0" : d.stripTrailingZeros().toPlainString();
    }

    /** Plain machine text for saving or for a text field. */
    public static String machine(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    // ---------------------------------------------------------------- internals

    private static String number(BigDecimal value, int decimals, boolean grouped) {
        if (value == null) {
            return "n/a";
        }
        BigDecimal rounded = value.setScale(decimals, RoundingMode.HALF_UP);
        String plain = rounded.abs().toPlainString();
        int dot = plain.indexOf('.');
        String whole = dot < 0 ? plain : plain.substring(0, dot);
        String fraction = dot < 0 ? "" : plain.substring(dot + 1);

        String groupSeparator = isSa() ? SA_GROUP : ",";
        String decimalSeparator = isSa() ? "," : ".";
        String body = (grouped ? groupThousands(whole, groupSeparator) : whole)
                + (fraction.isEmpty() ? "" : decimalSeparator + fraction);
        // A value that rounds to zero is shown without a minus sign
        return rounded.signum() < 0 ? "-" + body : body;
    }

    /** Puts the separator between each group of three digits, counting from the right. */
    static String groupThousands(String digits, String separator) {
        StringBuilder sb = new StringBuilder();
        int first = digits.length() % 3;
        if (first > 0) {
            sb.append(digits, 0, first);
        }
        for (int i = first; i < digits.length(); i += 3) {
            if (sb.length() > 0) {
                sb.append(separator);
            }
            sb.append(digits, i, i + 3);
        }
        return sb.toString();
    }

    /** The exact decimal for a number, or null when it is not finite (NaN or infinity). */
    private static BigDecimal toDecimal(double value) {
        return Double.isFinite(value) ? BigDecimal.valueOf(value) : null;
    }

    private static BigDecimal toDecimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Double || value instanceof Float) {
            return toDecimal(((Number) value).doubleValue());
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.longValue());
        }
        return null;
    }
}
