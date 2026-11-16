package application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

public class VatCalculator {

    static final String MODE_ADD = "Add VAT to a price (excl. → incl.)";
    static final String MODE_REMOVE = "Remove VAT from a price (incl. → excl.)";

    public static VBox createLayout(boolean darkTheme) {
        String vatPercent = AppSettings.vatPercentText();

        Label errorLabel = UiUtil.createErrorLabel();

        ComboBox<String> directionCombo = new ComboBox<>();
        directionCombo.getItems().addAll(MODE_ADD, MODE_REMOVE);
        directionCombo.setValue(MODE_ADD);

        TextField amountField = new TextField();
        amountField.setPromptText("e.g. 100.00");

        ResultCard result = new ResultCard("VAT AMOUNT (" + vatPercent + "%)");
        Label exclOut = result.addRow("Amount Excluding VAT");
        Label inclOut = result.addRow("Amount Including VAT");
        // The rate shown on the card follows Settings, so it never goes stale
        AppSettings.addChangeListener(() -> result.setCaption("VAT AMOUNT (" + AppSettings.vatPercentText() + "%)"));

        // The amount without VAT from the last calculation, for sending to Margin & Markup
        BigDecimal[] exclusiveResult = {null};

        Button calcBtn = new Button("Calculate VAT");
        Button resetBtn = new Button("Reset");
        Button useBtn = new Button("Use Amount Excl. VAT in Margin & Markup");
        useBtn.getStyleClass().add("pill-button");
        useBtn.setMaxWidth(Double.MAX_VALUE);
        useBtn.setOnAction(e -> {
            if (exclusiveResult[0] == null) {
                UiUtil.showError(errorLabel, "Calculate the VAT first, then use the amount.");
            } else {
                Map<String, String> values = new HashMap<>();
                values.put("price", Money.machine(exclusiveResult[0]));
                CalcLinks.send("Margin & Markup", values);
            }
        });

        calcBtn.setOnAction(e -> {
            exclusiveResult[0] = null;
            try {
                UiUtil.clearInvalid(amountField);
                BigDecimal amount = UiUtil.money(amountField, "Amount", 0);
                boolean add = MODE_ADD.equals(directionCombo.getValue());

                BigDecimal excl;
                BigDecimal incl;
                if (add) {
                    excl = amount.setScale(2, RoundingMode.HALF_UP);
                    incl = FinanceMath.inclusiveFromExclusive(amount, AppSettings.vatRate());
                } else {
                    incl = amount.setScale(2, RoundingMode.HALF_UP);
                    excl = FinanceMath.exclusiveFromInclusive(amount, AppSettings.vatRate());
                }
                BigDecimal vat = incl.subtract(excl);

                UiUtil.showError(errorLabel, "");
                result.setHeadline(Money.format("R %,.2f", vat));
                exclOut.setText(Money.format("R %,.2f", excl));
                inclOut.setText(Money.format("R %,.2f", incl));
                exclusiveResult[0] = excl;

                String expression = (add ? "Excl. VAT: R" : "Incl. VAT: R") + String.format(Locale.ROOT, "%.2f", amount);
                String historyResult = String.format(Locale.ROOT, "Excl: R%.2f, VAT: R%.2f, Incl: R%.2f", excl, vat, incl);
                BusinessHistory.addEntry("Business - VAT", expression, historyResult);
            } catch (InputParser.InvalidInputException ex) {
                result.clear();
                UiUtil.showError(errorLabel, "Error: " + ex.getMessage());
            }
        });
        resetBtn.setOnAction(e -> {
            exclusiveResult[0] = null;
            CalcLayout.reset(errorLabel, result, amountField);
        });

        // Send to calculator: fills the direction and amount from a saved line
        HistoryLoader.register("Business - VAT", record -> {
            var m = HistoryLoader.find(record.expression, "(Excl|Incl)\\. VAT: R([\\d.,]+)");
            if (m == null) {
                return "This saved line could not be read.";
            }
            directionCombo.setValue("Excl".equals(m.group(1)) ? MODE_ADD : MODE_REMOVE);
            amountField.setText(m.group(2));
            return null;
        });

        // Sent from Margin & Markup or from a saved price: the amount is filled in, and the user presses Calculate
        CalcLinks.receiver("VAT", values -> {
            if (!values.containsKey("amount")) {
                return;
            }
            directionCombo.setValue("remove".equals(values.get("mode")) ? MODE_REMOVE : MODE_ADD);
            amountField.setText(values.get("amount"));
            UiUtil.clearInvalid(amountField);
            UiUtil.showError(errorLabel, "");
            result.clear();
            exclusiveResult[0] = null;
        });

        VBox actions = new VBox(10, CalcLayout.buttonRow(calcBtn, resetBtn), useBtn);
        return CalcLayout.page("Pricing & Fees", "VAT Calculator", errorLabel,
                CalcLayout.inputsCard(
                        CalcLayout.field(CalcLayout.label("Calculation:"), directionCombo),
                        CalcLayout.field(CalcLayout.label("Amount (R):"), amountField),
                        actions),
                result.root);
    }
}
