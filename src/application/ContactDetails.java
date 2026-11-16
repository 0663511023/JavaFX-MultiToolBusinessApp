package application;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Contact details kept as separate fields: street, suburb or city, postal code, phone, email and VAT number.
 * Each field is checked on its own, so a typing mistake is caught in the field where it was made.
 * Phone numbers are stored in one normalised form. Pure logic with no JavaFX, so it can be tested on its own.
 */
public final class ContactDetails {

    public static final int MAX_STREET = 120;
    public static final int MAX_SUBURB = 80;
    public static final int MAX_EMAIL = 254;

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9\\-]+(\\.[A-Za-z0-9\\-]+)*\\.[A-Za-z]{2,}");
    /** A South African number as typed: 0 then nine digits, such as 0821234567 or 0111234567. */
    private static final Pattern NATIONAL_PHONE = Pattern.compile("0\\d{9}");
    /** The same number with the country code, such as +27821234567 or 27821234567. */
    private static final Pattern INTERNATIONAL_PHONE = Pattern.compile("(\\+27|27)\\d{9}");
    private static final Pattern POSTAL_CODE = Pattern.compile("\\d{4}");
    private static final Pattern VAT_NUMBER = Pattern.compile("\\d{10}");

    public static final ContactDetails EMPTY = new ContactDetails("", "", "", "", "", "");

    public final String street;
    public final String suburb;
    public final String postalCode;
    /** The phone number in normalised form (0821234567 or +27821234567), or empty. */
    public final String phone;
    public final String email;
    public final String vatNumber;

    public ContactDetails(String street, String suburb, String postalCode, String phone, String email,
                          String vatNumber) {
        this.street = clean(street);
        this.suburb = clean(suburb);
        this.postalCode = clean(postalCode);
        this.phone = clean(phone);
        this.email = clean(email);
        this.vatNumber = clean(vatNumber);
    }

    public boolean hasVatNumber() {
        return !vatNumber.isEmpty();
    }

    public boolean isEmpty() {
        return street.isEmpty() && suburb.isEmpty() && postalCode.isEmpty()
                && phone.isEmpty() && email.isEmpty() && vatNumber.isEmpty();
    }

    /** The suburb or city and the postal code on one line, such as "Rosebank 2196". */
    public String placeLine() {
        if (suburb.isEmpty()) {
            return postalCode;
        }
        return postalCode.isEmpty() ? suburb : suburb + " " + postalCode;
    }

    /**
     * The lines printed on a document, in order. Empty fields are left out. The email line keeps the
     * address itself as its own text, so the PDF can highlight it as a link.
     */
    public List<String> printLines() {
        List<String> lines = new ArrayList<>();
        if (!street.isEmpty()) {
            lines.add(street);
        }
        if (!placeLine().isEmpty()) {
            lines.add(placeLine());
        }
        if (!phone.isEmpty()) {
            lines.add("Tel " + formatPhone(phone));
        }
        if (!email.isEmpty()) {
            lines.add("Email " + email);
        }
        return lines;
    }

    // ---------------------------------------------------------------- checks, one field at a time

    /** The street address: optional, at most 120 characters. */
    public static String checkStreet(String raw) throws InputParser.InvalidInputException {
        String value = clean(raw);
        if (value.length() > MAX_STREET) {
            throw new InputParser.InvalidInputException("The street address can be at most " + MAX_STREET
                    + " characters.");
        }
        return value;
    }

    /** The suburb or city: optional, at most 80 characters. */
    public static String checkSuburb(String raw) throws InputParser.InvalidInputException {
        String value = clean(raw);
        if (value.length() > MAX_SUBURB) {
            throw new InputParser.InvalidInputException("The suburb or city can be at most " + MAX_SUBURB
                    + " characters.");
        }
        return value;
    }

    /** The South African postal code: optional, but when given it must be four digits, such as 2196. */
    public static String checkPostalCode(String raw) throws InputParser.InvalidInputException {
        String value = clean(raw);
        if (!value.isEmpty() && !POSTAL_CODE.matcher(value).matches()) {
            throw new InputParser.InvalidInputException("The postal code must be four digits, for example 2196.");
        }
        return value;
    }

    /**
     * A South African phone number: optional. Spaces, dashes, brackets and dots are ignored. Returns the
     * normalised number, 0821234567 or +27821234567.
     */
    public static String checkPhone(String raw) throws InputParser.InvalidInputException {
        String value = clean(raw);
        if (value.isEmpty()) {
            return "";
        }
        String digits = value.replaceAll("[\\s\\-().]", "");
        if (NATIONAL_PHONE.matcher(digits).matches()) {
            return digits;
        }
        if (INTERNATIONAL_PHONE.matcher(digits).matches()) {
            return digits.startsWith("+") ? digits : "+" + digits;
        }
        throw new InputParser.InvalidInputException(
                "Enter a South African phone number, such as 082 123 4567 or +27 82 123 4567.");
    }

    /** An email address: optional. It must look like name@example.co.za, with no spaces. */
    public static String checkEmail(String raw) throws InputParser.InvalidInputException {
        String value = clean(raw);
        if (value.isEmpty()) {
            return "";
        }
        if (value.length() > MAX_EMAIL) {
            throw new InputParser.InvalidInputException("An email address can be at most " + MAX_EMAIL + " characters.");
        }
        int at = value.indexOf('@');
        if (at < 0) {
            throw new InputParser.InvalidInputException(
                    "An email address needs an @ symbol, such as name@example.co.za.");
        }
        if (value.indexOf('@', at + 1) >= 0) {
            throw new InputParser.InvalidInputException("An email address can have only one @ symbol.");
        }
        if (!value.substring(at + 1).contains(".")) {
            throw new InputParser.InvalidInputException(
                    "Add the domain after the @, ending in a full suffix such as .co.za or .com.");
        }
        if (!EMAIL.matcher(value).matches() || value.contains("..")) {
            throw new InputParser.InvalidInputException(
                    "Check the email address. Use letters, numbers and . _ % + - before the @, with no spaces.");
        }
        return value;
    }

    /** A VAT number: optional, and when given it must be 10 digits. Spaces are ignored. */
    public static String checkVatNumber(String raw) throws InputParser.InvalidInputException {
        String value = clean(raw).replace(" ", "");
        if (value.isEmpty()) {
            return "";
        }
        if (!VAT_NUMBER.matcher(value).matches()) {
            throw new InputParser.InvalidInputException("The VAT number must be 10 digits, for example 4123456789.");
        }
        return value;
    }

    /** A normalised phone number in the form people write it: 082 123 4567 or +27 82 123 4567. */
    public static String formatPhone(String normalised) {
        if (normalised == null || normalised.isEmpty()) {
            return "";
        }
        if (normalised.startsWith("+27") && normalised.length() == 12) {
            String d = normalised.substring(3);
            return "+27 " + d.substring(0, 2) + " " + d.substring(2, 5) + " " + d.substring(5);
        }
        if (normalised.length() == 10) {
            return normalised.substring(0, 3) + " " + normalised.substring(3, 6) + " " + normalised.substring(6);
        }
        return normalised;
    }

    private static String clean(String raw) {
        return raw == null ? "" : raw.trim();
    }
}
