package application;

import java.math.BigDecimal;
import java.util.Locale;

import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

public class MerchantFeeCalculator {

    static final String PRESET_CARD = "Standard Credit Card (3.5% + R2.00)";
    static final String PRESET_EFT = "Instant EFT / Ozow (1.5% + R0.00)";
    static final String PRESET_CUSTOM = "Custom Rates";

    static final String MODE_FORWARD = "Amount in → net payout out";
    static final String MODE_REVERSE = "Desired payout → amount to charge";

    /** Fixed rates are shown as read-only fields, so they look different from the ones you can type in. */
    private static void setLocked(TextField field, boolean locked) {
        field.setEditable(!locked);
        field.getStyleClass().remove("read-only");
        if (locked) {
            field.getStyleClass().add("read-only");
        }
    }

    public static VBox createLayout(boolean darkTheme) {
        Label errorLabel = UiUtil.createErrorLabel();
        String vatPercent = AppSettings.vatPercentText();

        ComboBox<String> directionCombo = new ComboBox<>();
        directionCombo.getItems().addAll(MODE_FORWARD, MODE_REVERSE);
        directionCombo.setValue(MODE_FORWARD);

        Label amountLabel = CalcLayout.label("Transaction Amount (R):");
        TextField amountField = new TextField();
        amountField.setPromptText("e.g. 1500.00");

        ComboBox<String> presetCombo = new ComboBox<>();
        presetCombo.getItems().addAll(PRESET_CARD, PRESET_EFT, PRESET_CUSTOM);
        presetCombo.setValue(PRESET_CARD);

        Label rateLabel = CalcLayout.label("Percentage Fee (%):");
        TextField rateField = new TextField("3.5");
        setLocked(rateField, true);

        Label fixedLabel = CalcLayout.label("Fixed Fee per Transaction (R):");
        TextField fixedField = new TextField("2.00");
        setLocked(fixedField, true);

        presetCombo.setOnAction(e -> {
            String selected = presetCombo.getValue();
            boolean custom = PRESET_CUSTOM.equals(selected);
            if (PRESET_CARD.equals(selected)) {
                rateField.setText("3.5");
                fixedField.setText("2.00");
            } else if (PRESET_EFT.equals(selected)) {
                rateField.setText("1.5");
                fixedField.setText("0.00");
            } else if (custom) {
                rateField.setText("0.00");
                fixedField.setText("0.00");
            }
            setLocked(rateField, !custom);
            setLocked(fixedField, !custom);
        });


        ResultCard result = new ResultCard("NET PAYOUT TO MERCHANT");
        Label grossOut = result.addRow("Transaction Amount");
        Label pctFeeOut = result.addRow("Percentage Fee");
        Label fixedFeeOut = result.addRow("Fixed Fee");
        Label vatOut = result.addRow("VAT on Fees (" + vatPercent + "%)");
        AppSettings.addChangeListener(() ->
                result.renameRow(vatOut, "VAT on Fees (" + AppSettings.vatPercentText() + "%)"));
        Label totalFeeOut = result.addRow("Total Deductions (incl. VAT)");
        // The result card follows the direction. Forward: the net payout you receive.
        // Reverse: the amount to charge, with the payout you want shown beneath it.
        directionCombo.valueProperty().addListener((obs, oldMode, newMode) -> {
            boolean reverse = MODE_REVERSE.equals(newMode);
            amountLabel.setText(reverse ? "Desired Payout (R):" : "Transaction Amount (R):");
            amountField.setPromptText(reverse ? "e.g. 1400.00" : "e.g. 1500.00");
            result.setCaption(reverse ? "AMOUNT TO CHARGE" : "NET PAYOUT TO MERCHANT");
            result.renameRow(grossOut, reverse ? "Net Payout to Merchant" : "Transaction Amount");
            result.clear(); // old figures would sit under the new labels, so they go
        });

        Button calcBtn = new Button("Calculate Fees");
        Button resetBtn = new Button("Reset");

        calcBtn.setOnAction(e -> {
            boolean reverse = MODE_REVERSE.equals(directionCombo.getValue());
            try {
                UiUtil.clearInvalid(amountField, rateField, fixedField);
                BigDecimal entered = UiUtil.money(amountField, reverse ? "Desired payout" : "Transaction amount", 0);
                // The fee box holds a percentage, so divide by 100 to get the fraction used in the sums
                BigDecimal feeRate = UiUtil.money(rateField, "Percentage fee", 0).movePointLeft(2);
                BigDecimal fixedFee = UiUtil.money(fixedField, "Fixed fee", 0);
                BigDecimal vatRate = AppSettings.vatRate();

                if (!FinanceMath.canCharge(feeRate, vatRate)) {
                    UiUtil.markInvalid(rateField);
                    throw new InputParser.InvalidInputException("Percentage fee is too high to calculate a charge.");
                }

                FinanceMath.Fees fees = reverse
                        ? FinanceMath.chargeForPayout(entered, feeRate, fixedFee, vatRate)
                        : FinanceMath.forwardFees(entered, feeRate, fixedFee, vatRate);

                if (!reverse && fees.net.signum() < 0) {
                    UiUtil.markInvalid(amountField);
                    throw new InputParser.InvalidInputException(
                            "The fees are more than the transaction amount, so nothing would be paid out.");
                }
                UiUtil.showError(errorLabel, "");
                result.setHeadline(Money.format("R %,.2f", reverse ? fees.amount : fees.net));
                grossOut.setText(Money.format("R %,.2f", reverse ? fees.net : fees.amount));
                pctFeeOut.setText(Money.format("R %,.2f", fees.percentFee));
                fixedFeeOut.setText(Money.format("R %,.2f", fees.fixedFee));
                vatOut.setText(Money.format("R %,.2f", fees.vat));
                totalFeeOut.setText(Money.format("R %,.2f", fees.total));

                String expression = reverse
                        ? "Target payout: R" + String.format(Locale.ROOT, "%.2f", entered)
                        : "Amount: R" + String.format(Locale.ROOT, "%.2f", fees.amount);
                String historyResult = reverse
                        ? "Charge: R" + String.format(Locale.ROOT, "%.2f", fees.amount)
                        : "Net payout: R" + String.format(Locale.ROOT, "%.2f", fees.net);
                BusinessHistory.addEntry("Business - Merchant Fee", expression, historyResult);
            } catch (InputParser.InvalidInputException ex) {
                result.clear();
                UiUtil.showError(errorLabel, "Error: " + ex.getMessage());
            }
        });
        resetBtn.setOnAction(e -> CalcLayout.reset(errorLabel, result, amountField));

        // Send to calculator: fills the direction and amount from a saved line. Rates are not saved, so they stay as they are.
        HistoryLoader.register("Business - Merchant Fee", record -> {
            var m = HistoryLoader.find(record.expression, "(Target payout|Amount): R([\\d.,]+)");
            if (m == null) {
                return "This saved line could not be read.";
            }
            directionCombo.setValue("Target payout".equals(m.group(1)) ? MODE_REVERSE : MODE_FORWARD);
            amountField.setText(m.group(2));
            return null;
        });

        return CalcLayout.page("Pricing & Fees", "E-Commerce & Merchant Fee Calculator", errorLabel,
                CalcLayout.inputsCard(
                        CalcLayout.field(CalcLayout.label("Direction:"), directionCombo),
                        CalcLayout.field(amountLabel, amountField),
                        CalcLayout.field(CalcLayout.label("Gateway Preset:"), presetCombo),
                        CalcLayout.field(rateLabel, rateField),
                        CalcLayout.field(fixedLabel, fixedField),
                        CalcLayout.buttonRow(calcBtn, resetBtn)),
                result.root);
    }
}
