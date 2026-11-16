package application;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

/** Section 11F: how much tax a retirement fund contribution saves, within the annual limit. */
public class RetirementCalculator {

    public static VBox createLayout(boolean darkTheme) {
        Label errorLabel = UiUtil.createErrorLabel();

        ComboBox<String> ageCombo = new ComboBox<>();
        ageCombo.getItems().addAll(SarsPayeEstimator.AGE_UNDER_65, SarsPayeEstimator.AGE_65_TO_74,
                SarsPayeEstimator.AGE_75_PLUS);
        ageCombo.setValue(SarsPayeEstimator.AGE_UNDER_65);

        TextField incomeField = new TextField();
        incomeField.setPromptText("e.g. 480000");
        TextField contribField = new TextField();
        contribField.setPromptText("e.g. 60000");

        ResultCard result = new ResultCard("ESTIMATED TAX SAVING");
        Label limitOut = result.addRow(Money.format("Deduction Limit (lower of %.1f%% or R %,.0f)", 27.5,
                (double) FinanceMath.S11F_CAP));
        Label deductibleOut = result.addRow("Deductible This Year");
        Label excessOut = result.addRow("Above the Limit (not deductible this year)");
        Label netCostOut = result.addRow("Contributions After Tax Saving");

        Button calcBtn = new Button("Calculate Tax Saving");
        Button resetBtn = new Button("Reset");

        // The same income and contribution, worked out in full in SARS PAYE (the contribution also reduces PAYE)
        Button payeBtn = new Button("See Full PAYE with This Contribution");
        payeBtn.getStyleClass().add("pill-button");
        payeBtn.setMaxWidth(Double.MAX_VALUE);
        payeBtn.setOnAction(e -> {
            Map<String, String> values = new HashMap<>();
            values.put("frequency", "Annual");
            values.put("gross", incomeField.getText() == null ? "" : incomeField.getText());
            values.put("retirement", contribField.getText() == null ? "" : contribField.getText());
            values.put("age", ageCombo.getValue());
            CalcLinks.send("SARS PAYE", values);
        });

        calcBtn.setOnAction(e -> {
            try {
                UiUtil.clearInvalid(incomeField, contribField);
                double income = UiUtil.number(incomeField, "Income", 0);
                double contributions = UiUtil.number(contribField, "Contributions", 0);
                String age = ageCombo.getValue();

                double limit = Math.min(FinanceMath.S11F_CAP, FinanceMath.S11F_PERCENT * income);
                double deductible = FinanceMath.section11fDeduction(contributions, income);
                double excess = Math.max(0, contributions - deductible);
                double saving = SarsPayeEstimator.annualTax(income, age)
                        - SarsPayeEstimator.annualTax(income - deductible, age);
                double netCost = contributions - saving;

                UiUtil.showError(errorLabel, "");
                result.setHeadline(Money.format("R %,.2f", saving));
                limitOut.setText(Money.format("R %,.2f", limit));
                deductibleOut.setText(Money.format("R %,.2f", deductible));
                excessOut.setText(Money.format("R %,.2f", excess));
                netCostOut.setText(Money.format("R %,.2f", netCost));

                BusinessHistory.addEntry("Business - Retirement",
                        String.format(Locale.ROOT, "Income R%.2f, contributions R%.2f (%s)", income, contributions, age),
                        String.format(Locale.ROOT, "Tax saving: R%.2f", saving));
            } catch (InputParser.InvalidInputException ex) {
                result.clear();
                UiUtil.showError(errorLabel, "Error: " + ex.getMessage());
            }
        });
        resetBtn.setOnAction(e -> CalcLayout.reset(errorLabel, result, incomeField, contribField));

        // Send to calculator: fills the inputs from a saved line
        HistoryLoader.register("Business - Retirement", record -> {
            var m = HistoryLoader.find(record.expression, "Income R([\\d.,]+), contributions R([\\d.,]+) \\((.+)\\)");
            if (m == null || !ageCombo.getItems().contains(m.group(3))) {
                return "This saved line could not be read.";
            }
            incomeField.setText(m.group(1));
            contribField.setText(m.group(2));
            ageCombo.setValue(m.group(3));
            return null;
        });

        return CalcLayout.page("Income & Tax", "Retirement Contributions (Section 11F)", errorLabel,
                CalcLayout.inputsCard(
                        CalcLayout.field(CalcLayout.label("Age Group:"), ageCombo),
                        CalcLayout.field(CalcLayout.label("Annual Remuneration / Taxable Income (R):"), incomeField),
                        CalcLayout.field(CalcLayout.label("Annual Retirement Fund Contributions (R):"), contribField),
                        new VBox(10, CalcLayout.buttonRow(calcBtn, resetBtn), payeBtn)),
                result.root);
    }
}
