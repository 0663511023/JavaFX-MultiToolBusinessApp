package application;

import java.math.BigDecimal;
import java.util.Locale;

import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

public class InterestCalculator {

    static final String UNIT_YEARS = "Years";
    static final String UNIT_MONTHS = "Months";
    static final String UNIT_DAYS = "Days";

    /** The term in years for a term given in the chosen unit. Months are twelfths of a year, days use 365. */
    static double yearsFor(double value, String unit) {
        if (UNIT_DAYS.equals(unit)) {
            return FinanceMath.termInYears(value, true);
        }
        if (UNIT_MONTHS.equals(unit)) {
            return value / 12.0;
        }
        return value;
    }

    /** The unit a saved line was worked out in, from the word the history line uses. */
    private static String unitFromWord(String word) {
        if ("days".equals(word)) {
            return UNIT_DAYS;
        }
        return "months".equals(word) ? UNIT_MONTHS : UNIT_YEARS;
    }

    public static VBox createLayout(boolean darkTheme) {
        Label errorLabel = UiUtil.createErrorLabel();

        TextField principalField = new TextField();
        principalField.setPromptText("e.g. 10000");
        TextField rateField = new TextField();
        rateField.setPromptText("e.g. 10.5");

        Label timeLabel = CalcLayout.label("Term Duration (Years):");
        TextField timeField = new TextField();
        timeField.setPromptText("e.g. 5");

        ComboBox<String> unitCombo = new ComboBox<>();
        unitCombo.getItems().addAll(UNIT_YEARS, UNIT_MONTHS, UNIT_DAYS);
        unitCombo.setValue(UNIT_YEARS);
        unitCombo.valueProperty().addListener((obs, oldUnit, unit) -> {
            timeLabel.setText("Term Duration (" + unit + "):");
            timeField.setPromptText(UNIT_DAYS.equals(unit) ? "e.g. 180"
                    : UNIT_MONTHS.equals(unit) ? "e.g. 60" : "e.g. 5");
        });

        ResultCard result = new ResultCard("COMPOUNDING VARIANCE EARNINGS");
        Label simpleOut = result.addRow("Simple Interest Total");
        Label compoundOut = result.addRow("Compound Interest Total");
        // Principal against the interest earned by compounding, shown only while a result is on screen
        ResultCharts.Pie split = new ResultCharts.Pie("PRINCIPAL VS INTEREST EARNED");

        Button calcBtn = new Button("Project Capital Growth");
        Button resetBtn = new Button("Reset");

        calcBtn.setOnAction(e -> {
            try {
                UiUtil.clearInvalid(principalField, rateField, timeField);
                double principal = UiUtil.number(principalField, "Principal", 0);
                double ratePercent = UiUtil.number(rateField, "Interest rate", 0);
                double annualRate = ratePercent / 100.0;
                double termValue = UiUtil.number(timeField, "Term", 0);
                String unit = unitCombo.getValue();
                double years = yearsFor(termValue, unit);

                double simpleTotal = principal * (1 + annualRate * years);
                double compoundTotal = principal * Math.pow(1 + annualRate, years);
                if (!Double.isFinite(simpleTotal) || !Double.isFinite(compoundTotal)) {
                    throw new InputParser.InvalidInputException(
                            "The result is too large to show. Try a smaller amount, rate or term.");
                }
                double growthVariance = compoundTotal - simpleTotal;

                UiUtil.showError(errorLabel, "");
                result.setHeadline(Money.format("R %,.2f", growthVariance));
                simpleOut.setText(Money.format("R %,.2f", simpleTotal));
                compoundOut.setText(Money.format("R %,.2f", compoundTotal));
                split.show("Principal", BigDecimal.valueOf(principal), "Interest earned (compound)",
                        BigDecimal.valueOf(compoundTotal - principal));

                String logInput = String.format(Locale.ROOT, "R%.2f @ %s%% for %s %s",
                        principal, Money.machine(ratePercent), Money.machine(termValue),
                        unit.toLowerCase(Locale.ROOT));
                BusinessHistory.addEntry("Business - Interest Engine", logInput,
                        String.format(Locale.ROOT, "Compound: R%.2f", compoundTotal));
            } catch (InputParser.InvalidInputException ex) {
                result.clear();
                split.hide();
                UiUtil.showError(errorLabel, "Error: " + ex.getMessage());
            }
        });
        resetBtn.setOnAction(e -> {
            CalcLayout.reset(errorLabel, result, principalField, rateField, timeField);
            split.hide();
        });

        // Send to calculator: fills the inputs from a saved line
        HistoryLoader.register("Business - Interest Engine", record -> {
            var m = HistoryLoader.find(record.expression,
                    "R([\\d.,]+) @ ([\\d.,]+)% for ([\\d.,]+) (years|months|days)");
            if (m == null) {
                return "This saved line could not be read.";
            }
            principalField.setText(m.group(1));
            rateField.setText(m.group(2));
            timeField.setText(m.group(3));
            unitCombo.setValue(unitFromWord(m.group(4)));
            return null;
        });

        return CalcLayout.page("Savings & Loans", "Simple vs. Compound Interest Growth Engine", errorLabel,
                CalcLayout.inputsCard(
                        CalcLayout.field(CalcLayout.label("Principal Amount (R):"), principalField),
                        CalcLayout.field(CalcLayout.label("Annual Interest Rate (%):"), rateField),
                        CalcLayout.field(timeLabel, timeField),
                        CalcLayout.field(CalcLayout.label("Duration Unit:"), unitCombo),
                        CalcLayout.buttonRow(calcBtn, resetBtn)),
                new VBox(16, result.root, split.card));
    }
}
