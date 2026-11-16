package application;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

/**
 * Margin & markup tool. The maths lives in FinanceMath, in exact decimals rounded to cents.
 *
 *   Margin %  = profit / selling price x 100
 *   Markup %  = profit / cost x 100
 *   Price from target margin m:  cost / (1 - m/100), rounded up to the cent
 *   Price from target markup k:  cost x (1 + k/100), rounded up to the cent
 */
public class MarginCalculator {

    static final String MODE_PRICE_TO_MARGIN = "Find margin: cost & selling price";
    static final String MODE_MARGIN_TO_PRICE = "Find price: cost & target margin %";
    static final String MODE_MARKUP_TO_PRICE = "Find price: cost & target markup %";

    public static VBox createLayout(boolean darkTheme) {
        Label errorLabel = UiUtil.createErrorLabel();

        ComboBox<String> modeCombo = new ComboBox<>();
        modeCombo.getItems().addAll(MODE_PRICE_TO_MARGIN, MODE_MARGIN_TO_PRICE, MODE_MARKUP_TO_PRICE);
        modeCombo.setValue(MODE_PRICE_TO_MARGIN);

        Label costLabel = CalcLayout.label("Cost Price (R):");
        TextField costField = new TextField();
        costField.setPromptText("e.g. 80");

        Label secondLabel = CalcLayout.label("Selling Price (R):");
        TextField secondField = new TextField();
        secondField.setPromptText("e.g. 120");

        modeCombo.valueProperty().addListener((obs, oldMode, mode) -> {
            if (MODE_MARGIN_TO_PRICE.equals(mode)) {
                secondLabel.setText("Target Margin (%):");
                secondField.setPromptText("e.g. 25");
            } else if (MODE_MARKUP_TO_PRICE.equals(mode)) {
                secondLabel.setText("Target Markup (%):");
                secondField.setPromptText("e.g. 50");
            } else {
                secondLabel.setText("Selling Price (R):");
                secondField.setPromptText("e.g. 120");
            }
        });

        ResultCard result = new ResultCard("GROSS MARGIN");
        Label sellOut = result.addRow("Selling Price");
        Label profitOut = result.addRow("Profit per Unit");
        Label markupOut = result.addRow("Markup");
        Label noteOut = result.addNote();
        // The last selling price, for the quote button. Cleared whenever the figures are no longer current.
        BigDecimal[] quotable = {null};
        // Charts and the what-if table under the result. They show only while a result is on screen.
        ResultCharts.Pie breakdown = new ResultCharts.Pie("COST VS PROFIT");
        ResultCharts.Sensitivity sensitivity = new ResultCharts.Sensitivity("WHAT IF THE COST CHANGES");
        // The last full calculation, for sending to other tools. Cleared whenever the figures are no longer current.
        FinanceMath.Pricing[] latest = {null};
        Runnable hideExtras = () -> {
            latest[0] = null;
            breakdown.hide();
            sensitivity.hide();
        };

        // A saved client's usual markup sets the calculator to find the price from that markup
        ComboBox<ClientProfile> clientCombo = ClientsView.clientPicker();
        clientCombo.valueProperty().addListener((obs, oldClient, client) -> {
            if (client != null && client.markupPercent != null) {
                modeCombo.setValue(MODE_MARKUP_TO_PRICE);
                secondField.setText(Money.machine(client.markupPercent));
            }
        });

        // The card follows the calculation. Price → margin: the margin is the headline.
        // Target margin or markup: the selling price you should charge is the headline.
        modeCombo.valueProperty().addListener((obs, oldMode, mode) -> {
            boolean priceMode = MODE_PRICE_TO_MARGIN.equals(mode);
            result.setCaption(priceMode ? "GROSS MARGIN" : "SELLING PRICE");
            result.renameRow(sellOut, priceMode ? "Selling Price" : "Gross Margin");
            result.clear(); // old figures would sit under the new labels, so they go
            hideExtras.run();
            quotable[0] = null;
        });

        Button calcBtn = new Button("Calculate");
        Button resetBtn = new Button("Reset");

        Button quoteBtn = new Button("Create Quote or Invoice...");
        quoteBtn.setOnAction(e -> {
            if (quotable[0] == null) {
                UiUtil.showError(errorLabel, "Calculate the price first, then create the quote or invoice.");
            } else {
                QuoteDialog.open(quoteBtn.getScene().getWindow(),
                        QuoteDialog.Terms.free("Product or service", quotable[0]), clientCombo.getValue());
            }
        });

        // Links to other tools, for the figures they share. Each needs a calculation on screen first.
        Button compareBtn = new Button("Compare in Scenario Comparison");
        compareBtn.getStyleClass().add("pill-button");
        compareBtn.setMaxWidth(Double.MAX_VALUE);
        compareBtn.setOnAction(e -> {
            if (latest[0] == null) {
                UiUtil.showError(errorLabel, "Calculate the price first, then compare it.");
                return;
            }
            Map<String, String> values = new HashMap<>();
            values.put("A.name", "Margin & Markup");
            values.put("A.cost", Money.machine(latest[0].cost));
            values.put("A.byMarkup", "false");
            values.put("A.value", Money.machine(latest[0].sellingPrice));
            CalcLinks.send("Scenario Comparison", values);
        });
        Button addVatBtn = new Button("Add VAT to this Price");
        addVatBtn.getStyleClass().add("pill-button");
        addVatBtn.setMaxWidth(Double.MAX_VALUE);
        addVatBtn.setOnAction(e -> {
            if (latest[0] == null) {
                UiUtil.showError(errorLabel, "Calculate the price first, then add VAT to it.");
                return;
            }
            Map<String, String> values = new HashMap<>();
            values.put("mode", "add");
            values.put("amount", Money.machine(latest[0].sellingPrice));
            CalcLinks.send("VAT", values);
        });

        calcBtn.setOnAction(e -> {
            quotable[0] = null;
            hideExtras.run();
            String mode = modeCombo.getValue();
            try {
                UiUtil.clearInvalid(costField, secondField);
                BigDecimal cost = UiUtil.money(costField, "Cost price", 0);
                FinanceMath.Pricing r;
                BigDecimal entered;
                if (MODE_PRICE_TO_MARGIN.equals(mode)) {
                    entered = UiUtil.money(secondField, "Selling price", 0);
                    r = FinanceMath.fromPrice(cost, entered);
                } else if (MODE_MARGIN_TO_PRICE.equals(mode)) {
                    entered = UiUtil.money(secondField, "Target margin", 0);
                    r = FinanceMath.fromTargetMargin(cost, entered);
                } else {
                    entered = UiUtil.money(secondField, "Target markup", 0);
                    r = FinanceMath.fromTargetMarkup(cost, entered);
                }

                UiUtil.showError(errorLabel, "");
                quotable[0] = r.sellingPrice;
                latest[0] = r;
                breakdown.show("Cost", r.cost, "Profit", r.profit);
                sensitivity.show(Scenarios.sensitivity(r.cost, r.sellingPrice));
                if (MODE_PRICE_TO_MARGIN.equals(mode)) {
                    result.setHeadline(Money.format("%.2f%%", r.marginPct));
                    sellOut.setText(Money.format("R %,.2f", r.sellingPrice));
                } else {
                    result.setHeadline(Money.format("R %,.2f", r.sellingPrice));
                    sellOut.setText(Money.format("%.2f%%", r.marginPct));
                }
                profitOut.setText(Money.format("R %,.2f", r.profit));
                markupOut.setText(r.markupPct == null
                        ? "n/a (cost is zero)"
                        : Money.format("%.2f%%", r.markupPct));

                if (r.profit.signum() < 0) {
                    noteOut.setText("You are selling below cost. This loses money on every unit.");
                    noteOut.getStyleClass().setAll("advice-high");
                } else {
                    noteOut.setText("");
                    noteOut.getStyleClass().setAll("output-label");
                }

                String expression = String.format(Locale.ROOT, "Cost R%.2f, %s", r.cost, describe(mode, entered.stripTrailingZeros().toPlainString()));
                String historyResult = String.format(Locale.ROOT, "Price R%.2f, margin %.2f%%, markup %s",
                        r.sellingPrice, r.marginPct,
                        r.markupPct == null ? "n/a" : String.format(Locale.ROOT, "%.2f%%", r.markupPct));
                BusinessHistory.addEntry("Business - Margin", expression, historyResult);
            } catch (InputParser.InvalidInputException ex) {
                result.clear();
                hideExtras.run();
                // Errors about the cost belong to the cost box; the rest belong to the second box
                if (ex.getMessage().startsWith("Cost")) {
                    UiUtil.markInvalid(costField);
                } else if (!UiUtil.isInvalid(costField)) {
                    UiUtil.markInvalid(secondField);
                }
                UiUtil.showError(errorLabel, "Error: " + ex.getMessage());
            }
        });
        resetBtn.setOnAction(e -> {
            quotable[0] = null;
            hideExtras.run();
            CalcLayout.reset(errorLabel, result, costField, secondField);
        });

        // Send to calculator: fills the inputs from a saved line. The user presses Calculate to run it.
        HistoryLoader.register("Business - Margin", record -> {
            var cost = HistoryLoader.find(record.expression, "Cost R([\\d.,]+)");
            var second = HistoryLoader.find(record.expression, "(?:target margin |target markup |selling price R)([\\d.,]+)");
            if (cost == null || second == null) {
                return "This saved line could not be read.";
            }
            String text = record.expression;
            modeCombo.setValue(text.contains("target margin") ? MODE_MARGIN_TO_PRICE
                    : text.contains("target markup") ? MODE_MARKUP_TO_PRICE : MODE_PRICE_TO_MARGIN);
            costField.setText(cost.group(1));
            secondField.setText(second.group(1));
            return null;
        });

        // Sent from VAT: the amount without VAT becomes the selling price. The user adds the cost and presses Calculate.
        CalcLinks.receiver("Margin & Markup", values -> {
            String price = values.get("price");
            if (price == null) {
                return;
            }
            modeCombo.setValue(MODE_PRICE_TO_MARGIN);
            secondField.setText(price);
            UiUtil.clearInvalid(costField, secondField);
            UiUtil.showError(errorLabel, "");
            hideExtras.run();
        });

        return CalcLayout.page("Pricing & Fees", "Margin & Markup Calculator", errorLabel,
                CalcLayout.inputsCard(
                        CalcLayout.field(CalcLayout.label("Saved Client (optional):"), clientCombo),
                        CalcLayout.field(CalcLayout.label("Calculation:"), modeCombo),
                        CalcLayout.field(costLabel, costField),
                        CalcLayout.field(secondLabel, secondField),
                        new VBox(10, CalcLayout.actionRows(calcBtn, resetBtn, quoteBtn), compareBtn, addVatBtn)),
                new VBox(16, result.root, breakdown.card, sensitivity.card));
    }

    private static String describe(String mode, String secondRaw) {
        if (MODE_MARGIN_TO_PRICE.equals(mode)) {
            return "target margin " + secondRaw + "%";
        }
        if (MODE_MARKUP_TO_PRICE.equals(mode)) {
            return "target markup " + secondRaw + "%";
        }
        return "selling price R" + secondRaw;
    }
}
