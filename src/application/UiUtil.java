package application;

import java.math.BigDecimal;

import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;

/** Small helpers shared by the calculator tabs. */
public final class UiUtil {

    /** Style class that draws a red border on a field with an error. */
    public static final String INVALID = "field-error";

    private UiUtil() {}

    // ------------------------------------------------------------ error labels

    /** A red message label placed under the tab's header. Hidden until it has text. */
    public static Label createErrorLabel() {
        Label label = new Label("");
        label.getStyleClass().add("error-label");
        label.setWrapText(true);
        hide(label);
        return label;
    }

    /** Shows the text in red under the header, or hides the label when the text is empty. */
    public static void showError(Label label, String text) {
        String value = text == null ? "" : text;
        label.setText(value);
        if (value.isEmpty()) {
            hide(label);
        } else {
            label.setVisible(true);
            label.setManaged(true);
        }
    }

    private static void hide(Label label) {
        label.setText("");
        label.setVisible(false);
        label.setManaged(false);
    }

    // ------------------------------------------------------------ red field borders

    /** Draws a red border around these fields. */
    public static void markInvalid(Control... fields) {
        for (Control field : fields) {
            if (!field.getStyleClass().contains(INVALID)) {
                field.getStyleClass().add(INVALID);
            }
        }
    }

    /** Removes the red border from these fields. */
    public static void clearInvalid(Control... fields) {
        for (Control field : fields) {
            field.getStyleClass().remove(INVALID);
        }
    }

    public static boolean isInvalid(Control field) {
        return field.getStyleClass().contains(INVALID);
    }

    // ------------------------------------------------------------ parsing that marks the field

    /** Reads an exact amount from the field, requiring at least min. Marks the field red if invalid. */
    public static BigDecimal money(TextField field, String name, double min)
            throws InputParser.InvalidInputException {
        try {
            return InputParser.decimalAtLeast(field.getText(), name, BigDecimal.valueOf(min));
        } catch (InputParser.InvalidInputException ex) {
            markInvalid(field);
            throw ex;
        }
    }

    /** Reads a number as a double from the field, requiring at least min. Marks the field red if invalid. */
    public static double number(TextField field, String name, double min)
            throws InputParser.InvalidInputException {
        return money(field, name, min).doubleValue();
    }

    /** Reads a number as a double from the field, requiring minExclusive < value <= maxInclusive. Marks it red if invalid. */
    public static double numberInRange(TextField field, String name, double minExclusive, double maxInclusive)
            throws InputParser.InvalidInputException {
        try {
            return InputParser.parseInRange(field.getText(), name, minExclusive, maxInclusive);
        } catch (InputParser.InvalidInputException ex) {
            markInvalid(field);
            throw ex;
        }
    }
}
