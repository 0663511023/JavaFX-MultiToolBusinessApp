package application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

public class VolumetricCalculator {

    static final String DIV_STANDARD = "5000 (standard)";
    static final String DIV_EXPRESS = "4000 (express)";
    static final String DIV_CUSTOM = "Custom";

    public static VBox createLayout(boolean darkTheme) {
        Label errorLabel = UiUtil.createErrorLabel();

        TextField lengthField = new TextField();
        lengthField.setPromptText("e.g. 40");
        TextField widthField = new TextField();
        widthField.setPromptText("e.g. 30");
        TextField heightField = new TextField();
        heightField.setPromptText("e.g. 20");
        TextField weightField = new TextField();
        weightField.setPromptText("e.g. 1.5");

        ComboBox<String> divisorCombo = new ComboBox<>();
        divisorCombo.getItems().addAll(DIV_STANDARD, DIV_EXPRESS, DIV_CUSTOM);
        divisorCombo.setValue(DIV_STANDARD);

        Label customDivLabel = CalcLayout.label("Custom Divisor:");
        TextField customDivField = new TextField();
        customDivField.setPromptText("e.g. 6000");
        customDivField.setDisable(true);

        divisorCombo.valueProperty().addListener((obs, oldDiv, div) -> customDivField.setDisable(!DIV_CUSTOM.equals(div)));

        ResultCard result = new ResultCard("BILLABLE WEIGHT");
        Label actualWeightOut = result.addRow("Actual Dead Weight");
        Label volOut = result.addRow("Volumetric Weight");
        Label adviceOut = result.addNote();

        Button calcBtn = new Button("Calculate Billable Weight");
        Button resetBtn = new Button("Reset");

        calcBtn.setOnAction(e -> {
            try {
                UiUtil.clearInvalid(lengthField, widthField, heightField, weightField, customDivField);
                double length = UiUtil.numberInRange(lengthField, "Length", 0, 10_000);
                double width = UiUtil.numberInRange(widthField, "Width", 0, 10_000);
                double height = UiUtil.numberInRange(heightField, "Height", 0, 10_000);
                double actualWeight = UiUtil.numberInRange(weightField, "Dead weight", 0, 100_000);

                double divisor = resolveDivisor(divisorCombo.getValue(), customDivField);

                double volumetricWeight = (length * width * height) / divisor;
                // Couriers bill in half-kilogram steps and never round down, so a part step is charged as a full one
                double billableWeight = roundUpToHalfKg(Math.max(actualWeight, volumetricWeight));

                UiUtil.showError(errorLabel, "");
                result.setHeadline(Money.format("%,.2f kg", billableWeight));
                actualWeightOut.setText(Money.format("%,.2f kg", actualWeight));
                volOut.setText(Money.format("%,.2f kg (÷ %,.0f)", volumetricWeight, divisor));

                if (volumetricWeight > actualWeight) {
                    adviceOut.setText("Volumetric weight is higher. The courier will bill on box dimensions.");
                    adviceOut.getStyleClass().setAll("advice-high");
                } else {
                    adviceOut.setText("Dead weight is higher. Standard mass billing applies.");
                    adviceOut.getStyleClass().setAll("advice-ok");
                }

                String logDesc = String.format(Locale.ROOT, "Box [%s x %s x %s cm] Dead: %s kg, ÷%s",
                        Money.machine(length), Money.machine(width), Money.machine(height),
                        Money.machine(actualWeight), plainNumber(divisor));
                BusinessHistory.addEntry("Business - Logistics", logDesc,
                        String.format(Locale.ROOT, "Billable: %.2f kg", billableWeight));
            } catch (InputParser.InvalidInputException ex) {
                result.clear();
                UiUtil.showError(errorLabel, "Error: " + ex.getMessage());
            }
        });
        resetBtn.setOnAction(e -> CalcLayout.reset(errorLabel, result,
                lengthField, widthField, heightField, weightField, customDivField));

        // Send to calculator: fills the inputs from a saved line
        HistoryLoader.register("Business - Logistics", record -> {
            var m = HistoryLoader.find(record.expression,
                    "Box \\[(.+?) x (.+?) x (.+?) cm\\] Dead: (.+?) kg, [^0-9]*([\\d.]+)");
            if (m == null) {
                return "This saved line could not be read.";
            }
            lengthField.setText(m.group(1));
            widthField.setText(m.group(2));
            heightField.setText(m.group(3));
            weightField.setText(m.group(4));
            // Compare the number, not the text, so a custom 50000 is not read as the standard 5000
            double divisor;
            try {
                divisor = InputParser.parse(m.group(5), "Divisor");
            } catch (InputParser.InvalidInputException ex) {
                return "This saved line could not be read.";
            }
            if (divisor == AppSettings.DIVISOR_5000) {
                divisorCombo.setValue(DIV_STANDARD);
            } else if (divisor == AppSettings.DIVISOR_4000) {
                divisorCombo.setValue(DIV_EXPRESS);
            } else {
                divisorCombo.setValue(DIV_CUSTOM);
                customDivField.setText(m.group(5));
            }
            return null;
        });

        return CalcLayout.page("Shipping", "Courier & Volumetric Shipping Weight Calculator", errorLabel,
                CalcLayout.inputsCard(
                        CalcLayout.field(CalcLayout.label("Length (cm):"), lengthField),
                        CalcLayout.field(CalcLayout.label("Width (cm):"), widthField),
                        CalcLayout.field(CalcLayout.label("Height (cm):"), heightField),
                        CalcLayout.field(CalcLayout.label("Actual Dead Weight (kg):"), weightField),
                        CalcLayout.field(CalcLayout.label("Courier Divisor:"), divisorCombo),
                        CalcLayout.field(customDivLabel, customDivField),
                        CalcLayout.buttonRow(calcBtn, resetBtn)),
                result.root);
    }

    /** A whole number without decimals, otherwise two decimals. Used in the saved line so it reloads exactly. */
    static String plainNumber(double value) {
        return value == Math.rint(value)
                ? String.format(Locale.ROOT, "%.0f", value)
                : String.format(Locale.ROOT, "%.2f", value);
    }

    /** Rounds a weight UP to the next half kilogram, e.g. 4.1 kg becomes 4.5 kg and 4.0 kg stays 4.0 kg. */
    static double roundUpToHalfKg(double kg) {
        // Six decimals first, so floating-point noise (2.0000000000000004) cannot push an exact step up
        BigDecimal halfSteps = BigDecimal.valueOf(kg).setScale(6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(2))
                .setScale(0, RoundingMode.CEILING);
        return halfSteps.divide(BigDecimal.valueOf(2)).doubleValue();
    }

    static double resolveDivisor(String choice, TextField customField) throws InputParser.InvalidInputException {
        if (DIV_EXPRESS.equals(choice)) {
            return AppSettings.DIVISOR_4000;
        }
        if (DIV_CUSTOM.equals(choice)) {
            return UiUtil.numberInRange(customField, "Custom divisor", 0, 100_000);
        }
        return AppSettings.DIVISOR_5000;
    }
}
