package application;

import java.util.Locale;

import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.VBox;

public class SarsPayeEstimator {

    public static final String AGE_UNDER_65 = "Under 65";
    public static final String AGE_65_TO_74 = "65 to 74";
    public static final String AGE_75_PLUS = "75+";

    static final String MONTHLY = "Monthly";
    static final String ANNUAL = "Annual";

    /** The most people the medical scheme credit covers: the main member and their dependants. */
    static final int MAX_MEDICAL_MEMBERS = 20;

    /**
     * Annual income tax after rebates, for 2026/27 (YoA 2027).
     * Public so other tools (e.g. the freelance and retirement tabs) use the same rules.
     */
    public static double annualTax(double annualIncome, String ageGroup) {
        return FinanceMath.annualTax(annualIncome, ageGroup);
    }

    public static VBox createLayout(boolean darkTheme) {
        Label errorLabel = UiUtil.createErrorLabel();

        ComboBox<String> ageCombo = new ComboBox<>();
        ageCombo.getItems().addAll(AGE_UNDER_65, AGE_65_TO_74, AGE_75_PLUS);
        ageCombo.setValue(AGE_UNDER_65);

        ComboBox<String> freqCombo = new ComboBox<>();
        freqCombo.getItems().addAll(MONTHLY, ANNUAL);
        freqCombo.setValue(MONTHLY);

        TextField amountField = new TextField();
        amountField.setPromptText("e.g. 25000");

        TextField medicalField = new TextField("0");
        medicalField.setPromptText("0 for no medical scheme");

        TextField schemeField = new TextField();
        schemeField.setPromptText("e.g. 3500, or blank for none");
        TextField expensesField = new TextField();
        expensesField.setPromptText("e.g. 12000, or blank for none");
        CheckBox disabledBox = new CheckBox("Disabled: you, your spouse or a child (ITR-DD form held)");

        Label retirementLabel = CalcLayout.label("Retirement Fund Contributions per Month (R, optional):");
        TextField retirementField = new TextField();
        retirementField.setPromptText("e.g. 3000, or blank for none");
        freqCombo.valueProperty().addListener((obs, oldFreq, freq) ->
                retirementLabel.setText(ANNUAL.equals(freq)
                        ? "Retirement Fund Contributions per Year (R, optional):"
                        : "Retirement Fund Contributions per Month (R, optional):"));

        ResultCard result = new ResultCard("NET TAKE-HOME PAY");
        Label grossOut = result.addRow("Gross Income");
        Label contributionOut = result.addRow("Retirement Contributions");
        Label deductionOut = result.addRow("Section 11F Deduction (reduces tax)");
        Label taxBeforeRebateOut = result.addRow("Tax Before Rebates");
        Label rebateOut = result.addRow("Rebate Applied");
        Label medicalOut = result.addRow("Medical Scheme Tax Credit (6A)");
        Label expensesOut = result.addRow("Medical Expenses Credit (6B), claimed at year-end");
        Label netTaxOut = result.addRow("Tax on Payslip (after 6A)");
        Label uifOut = result.addRow("UIF Contribution (1%)");
        result.addStaticNote("Take-home pay is after payslip tax, the retirement contribution and UIF. Section 6B is "
                + "claimed at year-end, so it is shown as a year-end credit and is not in take-home pay. "
                + "UIF is 1% of pay up to R17 712 a month. The disability rate needs an ITR-DD form on file; "
                + "this tool does not check it.");

        Button calcBtn = new Button("Calculate Tax & PAYE");
        Button resetBtn = new Button("Reset");

        calcBtn.setOnAction(e -> {
            boolean monthly = MONTHLY.equals(freqCombo.getValue());
            try {
                UiUtil.clearInvalid(amountField, retirementField, medicalField, schemeField, expensesField);
                double inputVal = UiUtil.number(amountField, "Gross earnings", 0);
                double contributions = optionalAmount(retirementField, "Retirement fund contributions");
                int members = medicalMembers(medicalField);
                double schemeMonthly = optionalAmount(schemeField, "Medical scheme contributions");
                double expenses = optionalAmount(expensesField, "Out-of-pocket medical expenses");
                boolean disabled = disabledBox.isSelected();
                String age = ageCombo.getValue();

                // The rules work on a year; the figures on screen follow the frequency the user chose.
                // Scheme contributions are entered per month, and out-of-pocket expenses per year.
                double perYear = monthly ? 12 : 1;
                FinanceMath.Paye p = FinanceMath.paye(inputVal * perYear, contributions * perYear, members, age,
                        schemeMonthly * 12, expenses, disabled);
                // Payslip tax is after 6A only: 6B is claimed at year-end, so it does not reduce monthly take-home
                double displayTax = p.payslipTax() / perYear;
                double displayUif = p.uif / perYear;
                // Take-home pay is after tax, the retirement contribution (taken from salary) and UIF
                double displayTakeHome = inputVal - displayTax - contributions - displayUif;
                String period = monthly ? "per month" : "per year";

                UiUtil.showError(errorLabel, "");
                result.setHeadline(Money.format("R %,.2f %s (estimate)", displayTakeHome, period));
                grossOut.setText(Money.format("R %,.2f", inputVal));
                contributionOut.setText(Money.format("R %,.2f", contributions));
                deductionOut.setText(Money.format("R %,.2f", p.deduction / perYear));
                taxBeforeRebateOut.setText(Money.format("R %,.2f", p.taxBeforeRebate / perYear));
                rebateOut.setText(Money.format("R %,.2f", p.rebate / perYear));
                medicalOut.setText(Money.format("R %,.2f", p.medicalCredit / perYear));
                expensesOut.setText(Money.format("R %,.2f", p.medicalExpensesCredit / perYear));
                netTaxOut.setText(Money.format("R %,.2f", displayTax));
                uifOut.setText(Money.format("R %,.2f", displayUif));

                BusinessHistory.addEntry("Business - SARS PAYE",
                        freqCombo.getValue() + " Gross: R" + amount(inputVal) + " (" + age + ")"
                                + " medical " + members + " retirement " + amount(contributions)
                                + " scheme " + amount(schemeMonthly) + " expenses " + amount(expenses)
                                + " disabled " + (disabled ? "yes" : "no"),
                        "Net Take-Home: R" + amount(displayTakeHome));
            } catch (InputParser.InvalidInputException ex) {
                result.clear();
                UiUtil.showError(errorLabel, "Error: " + ex.getMessage());
            }
        });
        resetBtn.setOnAction(e -> {
            CalcLayout.reset(errorLabel, result, amountField, retirementField, medicalField, schemeField, expensesField);
            medicalField.setText("0");
            disabledBox.setSelected(false);
        });

        // Send to calculator: fills the inputs from a saved line. Older lines leave the newer inputs empty.
        HistoryLoader.register("Business - SARS PAYE", record -> {
            var m = HistoryLoader.find(record.expression,
                    "(Monthly|Annual) Gross: R([\\d.,]+) \\((.+?)\\)(?: medical (\\d+))?(?: retirement ([\\d.,]+))?"
                            + "(?: scheme ([\\d.,]+) expenses ([\\d.,]+) disabled (yes|no))?");
            if (m == null || !ageCombo.getItems().contains(m.group(3))) {
                return "This saved line could not be read.";
            }
            freqCombo.setValue(m.group(1));
            amountField.setText(m.group(2));
            ageCombo.setValue(m.group(3));
            medicalField.setText(m.group(4) == null ? "0" : m.group(4));
            retirementField.setText(m.group(5) == null ? "" : m.group(5));
            schemeField.setText(m.group(6) == null ? "" : m.group(6));
            expensesField.setText(m.group(7) == null ? "" : m.group(7));
            disabledBox.setSelected("yes".equals(m.group(8)));
            return null;
        });

        // Sent from Retirement Contributions: the same income and contribution, worked out in full here
        CalcLinks.receiver("SARS PAYE", values -> {
            freqCombo.setValue(ANNUAL.equals(values.get("frequency")) ? ANNUAL : MONTHLY);
            amountField.setText(values.getOrDefault("gross", ""));
            retirementField.setText(values.getOrDefault("retirement", ""));
            if (ageCombo.getItems().contains(values.get("age"))) {
                ageCombo.setValue(values.get("age"));
            }
            UiUtil.clearInvalid(amountField, retirementField, medicalField, schemeField, expensesField);
            UiUtil.showError(errorLabel, "");
            result.clear();
        });

        // The medical inputs sit in a card that can be folded away. Members at 0 means no 6A credit.
        VBox medicalBox = new VBox(14,
                CalcLayout.field(CalcLayout.label("Medical Scheme Members (you and dependants, 0 for none):"),
                        medicalField),
                CalcLayout.field(CalcLayout.label("Medical Scheme Contributions per Month (R, optional):"),
                        schemeField),
                CalcLayout.field(CalcLayout.label("Out-of-Pocket Medical Expenses per Year (R, optional):"),
                        expensesField),
                disabledBox);
        TitledPane medicalPane = new TitledPane("Medical Credits, 6A and 6B (optional)", medicalBox);
        medicalPane.setCollapsible(true);
        medicalPane.setExpanded(true);
        medicalPane.setMaxWidth(Double.MAX_VALUE);

        return CalcLayout.page("Income & Tax", "SARS PAYE & Income Tax Estimator (2026/27)", errorLabel,
                CalcLayout.info("For planning and estimation only. Figures are annual calculations divided into pay "
                        + "periods, so a payslip can differ by a few cents or more. Section 6B is claimed at year-end, "
                        + "not on the payslip. This is not a substitute for certified payroll software or official SARS "
                        + "assessments."),
                CalcLayout.inputsCard(
                        CalcLayout.field(CalcLayout.label("Age Group:"), ageCombo),
                        CalcLayout.field(CalcLayout.label("Income Frequency:"), freqCombo),
                        CalcLayout.field(CalcLayout.label("Gross Earnings (R):"), amountField),
                        medicalPane,
                        CalcLayout.field(retirementLabel, retirementField),
                        CalcLayout.buttonRow(calcBtn, resetBtn)),
                result.root);
    }

    private static String amount(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /** An optional amount: blank means none. Otherwise it must be a valid amount. */
    private static double optionalAmount(TextField field, String name) throws InputParser.InvalidInputException {
        if (field.getText() == null || field.getText().isBlank()) {
            UiUtil.clearInvalid(field);
            return 0;
        }
        return UiUtil.number(field, name, 0);
    }

    /** The people on the medical scheme: blank or 0 for none, otherwise a whole number up to the limit. */
    private static int medicalMembers(TextField field) throws InputParser.InvalidInputException {
        if (field.getText() == null || field.getText().isBlank()) {
            UiUtil.clearInvalid(field);
            return 0;
        }
        double count = UiUtil.number(field, "Medical scheme members", 0);
        if (count != Math.floor(count) || count > MAX_MEDICAL_MEMBERS) {
            UiUtil.markInvalid(field);
            throw new InputParser.InvalidInputException(
                    "Medical scheme members must be a whole number from 0 to " + MAX_MEDICAL_MEMBERS + ".");
        }
        return (int) count;
    }
}
