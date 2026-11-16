package application;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * Safe numeric parsing for user-entered text, using exact decimals (BigDecimal) rather than doubles.
 *
 * Accepts: "1500", "1500.50", "1,500", "1 500", "1500,50" and "1 500,50" (SA decimal comma),
 * "1,500.50", "1.500,50", "R 1 500", "11.5%".
 * Rejects: empty text, letters, exponents such as "1e5", more than one decimal separator,
 * and amounts over MAX_AMOUNT.
 *
 * Rules for commas and dots:
 *   - A comma followed by exactly three digits in every group is a thousands separator ("1,500" = 1500).
 *   - Any other single comma is a decimal comma ("1,5" = 1.5).
 *   - When both appear, whichever comes last is the decimal separator.
 */
public final class InputParser {

    /** The largest amount the app accepts (R1 trillion). Keeps results well away from overflow. */
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000000");

    private static final Pattern PLAIN_NUMBER = Pattern.compile("[+-]?(\\d+\\.?\\d*|\\.\\d+)");
    private static final Pattern COMMA_THOUSANDS = Pattern.compile("\\d{1,3}(,\\d{3})+");
    private static final Pattern COMMA_THOUSANDS_DOT_DECIMAL = Pattern.compile("\\d{1,3}(,\\d{3})+\\.\\d+");
    private static final Pattern DOT_THOUSANDS_COMMA_DECIMAL = Pattern.compile("\\d{1,3}(\\.\\d{3})+,\\d+");

    private InputParser() {}

    public static class InvalidInputException extends Exception {
        public InvalidInputException(String message) {
            super(message);
        }
    }

    /** Reads the text as an exact decimal, or throws with a message that names the field. */
    public static BigDecimal parseDecimal(String raw, String fieldName) throws InvalidInputException {
        if (raw == null || raw.trim().isEmpty()) {
            throw new InvalidInputException(fieldName + " is required.");
        }
        String cleaned = raw.trim()
                .replaceAll("[\\s\\u00A0\\u202F]", "")   // spaces, including non-breaking ones
                .replaceFirst("^[Rr]", "")               // a leading rand sign
                .replace("%", "");

        boolean hasComma = cleaned.indexOf(',') >= 0;
        boolean hasDot = cleaned.indexOf('.') >= 0;
        if (hasComma && hasDot) {
            if (COMMA_THOUSANDS_DOT_DECIMAL.matcher(cleaned).matches()) {
                cleaned = cleaned.replace(",", "");
            } else if (DOT_THOUSANDS_COMMA_DECIMAL.matcher(cleaned).matches()) {
                cleaned = cleaned.replace(".", "").replace(',', '.');
            } else {
                throw invalid(fieldName);
            }
        } else if (hasComma) {
            if (COMMA_THOUSANDS.matcher(cleaned).matches()) {
                cleaned = cleaned.replace(",", "");
            } else if (cleaned.indexOf(',') == cleaned.lastIndexOf(',')) {
                cleaned = cleaned.replace(',', '.');
            } else {
                throw invalid(fieldName);
            }
        }

        if (!PLAIN_NUMBER.matcher(cleaned).matches()) {
            throw invalid(fieldName);
        }
        BigDecimal value = new BigDecimal(cleaned);
        if (value.abs().compareTo(MAX_AMOUNT) > 0) {
            throw new InvalidInputException(fieldName + " is too large. The limit is R1 trillion.");
        }
        return value;
    }

    /** Reads a number as a double. Prefer parseDecimal for money. */
    public static double parse(String raw, String fieldName) throws InvalidInputException {
        return parseDecimal(raw, fieldName).doubleValue();
    }

    /** Reads an exact decimal and requires value >= min. */
    public static BigDecimal decimalAtLeast(String raw, String fieldName, BigDecimal min)
            throws InvalidInputException {
        BigDecimal value = parseDecimal(raw, fieldName);
        if (value.compareTo(min) < 0) {
            throw new InvalidInputException(min.signum() == 0
                    ? fieldName + " cannot be negative."
                    : fieldName + " must be at least " + min.stripTrailingZeros().toPlainString() + ".");
        }
        return value;
    }

    /** Parses and requires value >= min. */
    public static double parseAtLeast(String raw, String fieldName, double min) throws InvalidInputException {
        return decimalAtLeast(raw, fieldName, BigDecimal.valueOf(min)).doubleValue();
    }

    /** Reads an exact decimal and requires minExclusive < value <= maxInclusive. */
    public static BigDecimal decimalInRange(String raw, String fieldName, BigDecimal minExclusive,
                                            BigDecimal maxInclusive) throws InvalidInputException {
        BigDecimal value = parseDecimal(raw, fieldName);
        if (value.compareTo(minExclusive) <= 0 || value.compareTo(maxInclusive) > 0) {
            throw new InvalidInputException(fieldName + " must be more than "
                    + minExclusive.stripTrailingZeros().toPlainString() + " and at most "
                    + maxInclusive.stripTrailingZeros().toPlainString() + ".");
        }
        return value;
    }

    /** Parses and requires minExclusive < value <= maxInclusive (for hours, percentages, etc.). */
    public static double parseInRange(String raw, String fieldName, double minExclusive, double maxInclusive)
            throws InvalidInputException {
        return decimalInRange(raw, fieldName, BigDecimal.valueOf(minExclusive),
                BigDecimal.valueOf(maxInclusive)).doubleValue();
    }

    private static InvalidInputException invalid(String fieldName) {
        return new InvalidInputException(fieldName + " must be a number, for example 1500 or 1500.50.");
    }
}
