package application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

public class FreelancerRateCalculator {

    /** Reads an optional amount: a blank box counts as zero. Marks the field red if the text is not a number. */
    private static double optionalNumber(TextField field, String name) throws InputParser.InvalidInputException {
        if (field.getText().trim().isEmpty()) {
            return 0;
        }
        return UiUtil.number(field, name, 0);
    }

    public static VBox createLayout(boolean darkTheme) {
        Label errorLabel = UiUtil.createErrorLabel();
        Label assumption = CalcLayout.info(
                "Assumes " + AppSettings.BILLABLE_WEEKS_PER_YEAR + " billable weeks per year (4 weeks leave buffer).");

        ComboBox<String> ageCombo = new ComboBox<>();
        ageCombo.getItems().addAll(SarsPayeEstimator.AGE_UNDER_65, SarsPayeEstimator.AGE_65_TO_74, SarsPayeEstimator.AGE_75_PLUS);
        ageCombo.setValue(SarsPayeEstimator.AGE_UNDER_65);

        TextField salaryField = new TextField();
        salaryField.setPromptText("e.g. 30000");
        TextField overheadField = new TextField();
        overheadField.setPromptText("e.g. 5000");
        TextField hoursField = new TextField();
        hoursField.setPromptText("e.g. 25");
        TextField expensesField = new TextField();
        expensesField.setPromptText("e.g. 800 (optional)");
        TextField provisionalField = new TextField();
        provisionalField.setPromptText("e.g. 0 (optional)");

        ResultCard result = new ResultCard("REQUIRED HOURLY RATE");
        Label grossOut = result.addRow("Gross Salary Needed (before tax)");
        Label taxOut = result.addRow("Estimated Income Tax");
        Label revenueOut = result.addRow("Total Monthly Revenue Needed");
        Label expensesOut = result.addRow("Other Business Expenses (per year)");
        Label provisionalOut = result.addRow("Provisional Tax Top-Up (per year)");
        Label annualOut = result.addRow("Annual Baseline Target");
        // The last required rate, for the quote button. Cleared whenever the figures are no longer current.
        BigDecimal[] quotable = {null};
        // The billable hours per week behind that rate, which the quote's quantity uses. Cleared with the rate.
        BigDecimal[] quotableHours = {null};

        // A saved client, and how their usual rate compares with the minimum just calculated
        ComboBox<ClientProfile> clientCombo = ClientsView.clientPicker();
        Label clientNote = CalcLayout.info("");
        Runnable updateClientNote = () -> {
            ClientProfile client = clientCombo.getValue();
            if (client == null || quotable[0] == null) {
                clientNote.setText("");
            } else if (client.hourlyRate == null) {
                clientNote.setText(client.name + " has no usual hourly rate saved.");
            } else if (client.hourlyRate.compareTo(quotable[0]) < 0) {
                clientNote.setText(client.name + "'s usual rate of " + Money.amount(client.hourlyRate)
                        + " per hour is below your minimum of " + Money.amount(quotable[0])
                        + " per hour. Quoting them at their usual rate would not cover your target.");
            } else {
                clientNote.setText(client.name + "'s usual rate of " + Money.amount(client.hourlyRate)
                        + " per hour covers your minimum of " + Money.amount(quotable[0]) + " per hour.");
            }
        };
        clientCombo.valueProperty().addListener((obs, a, b) -> updateClientNote.run());

        Button calcBtn = new Button("Calculate Minimum Hourly Rate");
        Button resetBtn = new Button("Reset");

        Button quoteBtn = new Button("Create Quote or Invoice...");
        quoteBtn.setOnAction(e -> {
            if (quotable[0] == null) {
                UiUtil.showError(errorLabel, "Calculate the rate first, then create the quote or invoice.");
            } else {
                // The rate and the hours are passed on locked, so the quote always matches this calculation
                QuoteDialog.open(quoteBtn.getScene().getWindow(),
                        QuoteDialog.Terms.hourly("Consulting services (billable hours per week)", quotable[0], quotableHours[0]),
                        clientCombo.getValue());
            }
        });

        calcBtn.setOnAction(e -> {
            quotable[0] = null;
            quotableHours[0] = null;
            updateClientNote.run();
            try {
                UiUtil.clearInvalid(salaryField, overheadField, hoursField, expensesField, provisionalField);
                double targetNetMonthly = UiUtil.number(salaryField, "Target net salary", 0);
                double overheads = UiUtil.number(overheadField, "Overheads", 0);
                double expensesMonthly = optionalNumber(expensesField, "Other business expenses");
                double provisionalAnnual = optionalNumber(provisionalField, "Provisional tax top-up");
                // Hours must be above zero: the rate is divided by the billable hours
                double hoursPerWeek = UiUtil.numberInRange(hoursField, "Billable hours", 0, 168);
                String age = ageCombo.getValue();

                double grossAnnual = FinanceMath.grossForNet(targetNetMonthly * 12, age);
                double taxAnnual = grossAnnual - targetNetMonthly * 12;
                double grossMonthly = grossAnnual / 12;

                double expensesPerYear = expensesMonthly * 12;
                double annualRevenueNeeded = grossAnnual + overheads * 12 + expensesPerYear + provisionalAnnual;
                double monthlyRevenueNeeded = annualRevenueNeeded / 12;
                double totalBillableHours = hoursPerWeek * AppSettings.BILLABLE_WEEKS_PER_YEAR;
                double requiredHourlyRate = annualRevenueNeeded / totalBillableHours;

                UiUtil.showError(errorLabel, "");
                // Rounded up to the cent, so the quoted rate never falls short of the rate needed
                BigDecimal quotedRate = FinanceMath.toCentsUp(BigDecimal.valueOf(requiredHourlyRate));
                quotable[0] = quotedRate;
                quotableHours[0] = BigDecimal.valueOf(hoursPerWeek).stripTrailingZeros();
                updateClientNote.run();
                result.setHeadline(Money.format("R %,.2f / hr", quotedRate.doubleValue()));
                grossOut.setText(Money.format("R %,.2f per month", grossMonthly));
                taxOut.setText(Money.format("R %,.2f per month", taxAnnual / 12));
                revenueOut.setText(Money.format("R %,.2f", monthlyRevenueNeeded));
                expensesOut.setText(Money.format("R %,.2f per year", expensesPerYear));
                provisionalOut.setText(Money.format("R %,.2f per year", provisionalAnnual));
                annualOut.setText(Money.format("R %,.2f", annualRevenueNeeded));

                // The hours are saved exactly, so "Send to calculator" brings back the same figure
                String hoursText = BigDecimal.valueOf(hoursPerWeek).stripTrailingZeros().toPlainString();
                String logInput = String.format(Locale.ROOT, "Net: R%.2f/mo + Overheads: R%.2f/mo + Expenses: R%.2f/mo, Provisional: R%.2f/yr, %s hrs/wk (%s)",
                        targetNetMonthly, overheads, expensesMonthly, provisionalAnnual, hoursText, age);
                BusinessHistory.addEntry("Business - Freelance Rate", logInput,
                        String.format(Locale.ROOT, "Rate: R%.2f/hr", quotedRate));
            } catch (InputParser.InvalidInputException ex) {
                result.clear();
                UiUtil.showError(errorLabel, "Error: " + ex.getMessage());
            }
        });
        resetBtn.setOnAction(e -> {
            quotable[0] = null;
            quotableHours[0] = null;
            updateClientNote.run();
            CalcLayout.reset(errorLabel, result, salaryField, overheadField, hoursField, expensesField,
                    provisionalField);
        });

        // Send to calculator: fills the inputs from a saved line
        HistoryLoader.register("Business - Freelance Rate", record -> {
            var m = HistoryLoader.find(record.expression,
                    "Net: R([\\d.,]+)/mo \\+ Overheads: R([\\d.,]+)/mo \\+ Expenses: R([\\d.,]+)/mo, "
                    + "Provisional: R([\\d.,]+)/yr, ([\\d.,]+) hrs/wk \\((.+)\\)");
            if (m == null || !ageCombo.getItems().contains(m.group(6))) {
                return "This saved line could not be read.";
            }
            salaryField.setText(m.group(1));
            overheadField.setText(m.group(2));
            expensesField.setText(m.group(3));
            provisionalField.setText(m.group(4));
            hoursField.setText(m.group(5));
            ageCombo.setValue(m.group(6));
            return null;
        });

        return CalcLayout.page("Income & Tax", "Freelance Hourly Rate & Break-Even Tool", errorLabel, assumption,
                CalcLayout.inputsCard(
                        CalcLayout.field(CalcLayout.label("Age Group:"), ageCombo),
                        CalcLayout.field(CalcLayout.label("Target Net Monthly Salary (R):"), salaryField),
                        CalcLayout.field(CalcLayout.label("Monthly Business Overheads (R):"), overheadField),
                        CalcLayout.field(CalcLayout.label("Billable Hours Per Week:"), hoursField),
                        CalcLayout.field(CalcLayout.label("Other Business Expenses per Month (R):"), expensesField),
                        CalcLayout.field(CalcLayout.label("Provisional Tax Top-Up per Year (R):"), provisionalField),
                        CalcLayout.field(CalcLayout.label("Saved Client (optional):"), clientCombo),
                        clientNote,
                        CalcLayout.actionRows(calcBtn, resetBtn, quoteBtn)),
                result.root);
    }
}
