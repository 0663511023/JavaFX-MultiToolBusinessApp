package application;

import java.math.BigDecimal;

/**
 * A saved client: who they are, their contact details, whether their quotes and invoices carry VAT, and the
 * rates usually agreed with them. Every rate is optional. Pure logic with no JavaFX, so it can be tested on its own.
 */
public final class ClientProfile {

    public static final int MAX_NAME_LENGTH = 80;
    private static final BigDecimal MAX_RATE = new BigDecimal("1000000");
    private static final BigDecimal MAX_MARKUP = new BigDecimal("10000");

    public final String name;
    public final ContactDetails contact;
    /** True when quotes and invoices for this client charge VAT (a VAT-registered client, for example). */
    public final boolean chargesVat;
    /** The usual hourly rate before VAT, or null when none is saved. */
    public final BigDecimal hourlyRate;
    /** The usual markup as a percentage, such as 40 for 40%, or null when none is saved. */
    public final BigDecimal markupPercent;

    public ClientProfile(String name, ContactDetails contact, boolean chargesVat,
                         BigDecimal hourlyRate, BigDecimal markupPercent) {
        this.name = name == null ? "" : name.trim();
        if (this.name.isEmpty()) {
            throw new IllegalArgumentException("A client needs a name.");
        }
        this.contact = contact == null ? ContactDetails.EMPTY : contact;
        this.chargesVat = chargesVat;
        this.hourlyRate = hourlyRate;
        this.markupPercent = markupPercent;
    }

    @Override
    public String toString() {
        return name;
    }

    /** The client name: required, at most 80 characters. */
    public static String checkName(String raw) throws InputParser.InvalidInputException {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            throw new InputParser.InvalidInputException("Enter the client's name.");
        }
        if (value.length() > MAX_NAME_LENGTH) {
            throw new InputParser.InvalidInputException("The client's name can be at most "
                    + MAX_NAME_LENGTH + " characters.");
        }
        return value;
    }

    /** The default hourly rate: optional. Blank gives null; otherwise zero or more, up to 1 000 000. */
    public static BigDecimal checkRate(String raw) throws InputParser.InvalidInputException {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        BigDecimal value = InputParser.decimalAtLeast(raw, "Default hourly rate", BigDecimal.ZERO);
        if (value.compareTo(MAX_RATE) > 0) {
            throw new InputParser.InvalidInputException("Default hourly rate is too large.");
        }
        return value;
    }

    /** The preferred markup: optional. Blank gives null; otherwise zero or more, up to 10 000 percent. */
    public static BigDecimal checkMarkup(String raw) throws InputParser.InvalidInputException {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        BigDecimal value = InputParser.decimalAtLeast(raw, "Preferred markup", BigDecimal.ZERO);
        if (value.compareTo(MAX_MARKUP) > 0) {
            throw new InputParser.InvalidInputException("Preferred markup is too large.");
        }
        return value;
    }
}
