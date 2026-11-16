package application;

import java.util.regex.Pattern;

import javafx.scene.control.TextFormatter;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Tooltip;

/**
 * Runs one field's check and marks the field red if it fails, so the user sees which field to fix.
 * Shared by the quote dialog and the Clients page.
 */
final class FieldCheck {

    /** A check that returns the cleaned value, or throws when the text is not acceptable. */
    @FunctionalInterface
    interface Checker<T> {
        T check(String text) throws InputParser.InvalidInputException;
    }

    private FieldCheck() {
    }

    /** The characters a phone number, e-mail address, postal code or VAT number can contain. */
    static final String PHONE_KEYS = "[0-9+\\s().\\-]{0,20}";
    static final String EMAIL_KEYS = "[A-Za-z0-9._%+\\-@]{0,254}";
    static final String POSTAL_KEYS = "[0-9]{0,4}";
    static final String VAT_KEYS = "[0-9 ]{0,12}";

    /**
     * Phone, email, postal code and VAT fields. A wrong key is refused as it is typed, and each field is checked
     * when the user leaves it, with the reason shown as a tooltip on the red field.
     */
    static void contactInput(TextInputControl phone, TextInputControl email, TextInputControl postal,
                             TextInputControl vat) {
        allow(phone, PHONE_KEYS);
        allow(email, EMAIL_KEYS);
        allow(postal, POSTAL_KEYS);
        allow(vat, VAT_KEYS);
        checkOnLeave(phone, ContactDetails::checkPhone);
        checkOnLeave(email, ContactDetails::checkEmail);
        checkOnLeave(postal, ContactDetails::checkPostalCode);
        checkOnLeave(vat, ContactDetails::checkVatNumber);
    }

    /** Lets a change through only when the whole text still matches the pattern. */
    static void allow(TextInputControl field, String pattern) {
        Pattern allowed = Pattern.compile(pattern);
        field.setTextFormatter(new TextFormatter<String>(change ->
                allowed.matcher(change.getControlNewText()).matches() ? change : null));
    }

    /** When the user leaves the field it is checked: a problem marks the field red and shows the reason. */
    static void checkOnLeave(TextInputControl field, Checker<?> checker) {
        field.focusedProperty().addListener((obs, wasFocused, focused) -> {
            if (focused) {
                return;
            }
            try {
                checker.check(field.getText());
                UiUtil.clearInvalid(field);
                field.setTooltip(null);
            } catch (InputParser.InvalidInputException ex) {
                UiUtil.markInvalid(field);
                field.setTooltip(new Tooltip(ex.getMessage()));
            }
        });
    }

    static <T> T check(TextInputControl field, Checker<T> checker) throws InputParser.InvalidInputException {
        try {
            return checker.check(field.getText());
        } catch (InputParser.InvalidInputException ex) {
            UiUtil.markInvalid(field);
            throw ex;
        }
    }
}
