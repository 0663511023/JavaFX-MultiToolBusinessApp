package application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

public class LoanCalculator {

    private static final BigDecimal MAX_RATE = BigDecimal.valueOf(100);

    static final String UNIT_YEARS = "Years";
    static final String UNIT_MONTHS = "Months";
    static final String UNIT_DAYS = "Days";

    /** The unit a saved line was worked out in, from the word the history line uses. */
    private static String unitFromWord(String word) {
        if ("days".equals(word)) {
            return UNIT_DAYS;
        }
        return "months".equals(word) ? UNIT_MONTHS : UNIT_YEARS;
    }

    public static VBox createLayout(boolean darkTheme) {
        Label errorLabel = UiUtil.createErrorLabel();

        TextField amountField = new TextField();
        amountField.setPromptText("e.g. 1000000");
        TextField rateField = new TextField();
        rateField.setPromptText("e.g. 11.5");

        Label termLabel = CalcLayout.label("Term (Years):");
        TextField termField = new TextField();
        termField.setPromptText("e.g. 20");
        ComboBox<String> unitCombo = new ComboBox<>();
        unitCombo.getItems().addAll(UNIT_YEARS, UNIT_MONTHS, UNIT_DAYS);
        unitCombo.setValue(UNIT_YEARS);
        unitCombo.valueProperty().addListener((obs, oldUnit, unit) -> {
            termLabel.setText("Term (" + unit + "):");
            termField.setPromptText(UNIT_DAYS.equals(unit) ? "e.g. 7300"
                    : UNIT_MONTHS.equals(unit) ? "e.g. 240" : "e.g. 20");
        });
        // The starting rate and term come from Settings, and are read only when this screen is built
        rateField.setText(AppSettings.loanRateText());
        termField.setText(AppSettings.loanYearsText());
        PendingChanges.applied("loan", PendingChanges.Action.RESTART, "Default loan rate and term",
                () -> AppSettings.loanRateText() + " / " + AppSettings.loanYearsText());

        ResultCard result = new ResultCard("MONTHLY INSTALMENT");
        Label paymentsOut = result.addRow("Number of Monthly Payments");
        Label totalOut = result.addRow("Total Repaid over Term");
        Label interestOut = result.addRow("Total Interest Paid");
        Label termNote = result.addNote();
        // Principal against interest, shown only while a result is on screen
        ResultCharts.Pie split = new ResultCharts.Pie("PRINCIPAL VS INTEREST");

        Button calcBtn = new Button("Calculate Repayments");
        Button resetBtn = new Button("Reset");

        calcBtn.setOnAction(e -> {
            try {
                UiUtil.clearInvalid(amountField, rateField, termField);
                BigDecimal principal = UiUtil.money(amountField, "Loan amount", 0);
                BigDecimal annualRate = UiUtil.money(rateField, "Interest rate", 0);
                if (annualRate.compareTo(MAX_RATE) > 0) {
                    UiUtil.markInvalid(rateField);
                    throw new InputParser.InvalidInputException("Interest rate must be at most 100%.");
                }
                String unit = unitCombo.getValue();
                double maxTerm = UNIT_DAYS.equals(unit) ? 36_500 : UNIT_MONTHS.equals(unit) ? 1_200 : 100;
                double termValue = UiUtil.numberInRange(termField, "Term", 0, maxTerm);

                int months;
                String note = "";
                if (UNIT_DAYS.equals(unit)) {
                    // A loan is paid monthly, so days are counted as months. The note says how it was rounded.
                    months = (int) Math.round(termValue / FinanceMath.DAYS_PER_MONTH);
                    // 16 days is the shortest term the message names, so anything shorter is refused
                    if (months < 1 || termValue < 16) {
                        UiUtil.markInvalid(termField);
                        throw new InputParser.InvalidInputException(
                                "Term must be at least 16 days, which is one monthly payment.");
                    }
                    note = Money.machine(termValue) + " days is " + months + " monthly payments. "
                            + "Days are counted at about 30.4 to the month, so the term is rounded to whole months.";
                } else {
                    // The term must be a whole number of months, so nothing is rounded without the user knowing
                    double exactMonths = UNIT_MONTHS.equals(unit) ? termValue : termValue * 12;
                    months = (int) Math.round(exactMonths);
                    if (Math.abs(exactMonths - months) > 1e-6) {
                        UiUtil.markInvalid(termField);
                        throw new InputParser.InvalidInputException("Term must be a whole number of months, "
                                + "for example 20 years, 240 months or 20.25 years.");
                    }
                    if (months < 1) {
                        UiUtil.markInvalid(termField);
                        throw new InputParser.InvalidInputException("Term must be at least one month.");
                    }
                }

                // The instalment is rounded to the cent. Repayments are that instalment times the months,
                // except on a 0% loan, where the principal is simply split. Interest is never shown below zero.
                BigDecimal instalment = FinanceMath.loanInstalment(principal, annualRate, months);
                BigDecimal totalRepaid = annualRate.signum() == 0
                        ? principal.setScale(2, RoundingMode.HALF_UP)
                        : instalment.multiply(BigDecimal.valueOf(months));
                if (totalRepaid.compareTo(principal) < 0) {
                    totalRepaid = principal.setScale(2, RoundingMode.HALF_UP);
                }
                BigDecimal totalInterest = totalRepaid.subtract(principal);

                UiUtil.showError(errorLabel, "");
                result.setHeadline(Money.format("R %,.2f", instalment));
                paymentsOut.setText(String.valueOf(months));
                totalOut.setText(Money.format("R %,.2f", totalRepaid));
                interestOut.setText(Money.format("R %,.2f", totalInterest));
                termNote.setText(note);
                split.show("Principal", principal, "Interest", totalInterest);

                String word = UNIT_DAYS.equals(unit) ? "days" : UNIT_MONTHS.equals(unit) ? "months" : "yrs";
                String expression = String.format(Locale.ROOT, "Loan R%.2f @ %s%% over %s %s",
                        principal, annualRate.stripTrailingZeros().toPlainString(), Money.machine(termValue), word);
                BusinessHistory.addEntry("Business - Loan Repayment", expression,
                        String.format(Locale.ROOT, "Instalment: R%.2f/mo, interest R%.2f", instalment, totalInterest));
            } catch (InputParser.InvalidInputException | IllegalArgumentException ex) {
                result.clear();
                split.hide();
                UiUtil.showError(errorLabel, "Error: " + ex.getMessage());
            }
        });
        resetBtn.setOnAction(e -> {
            CalcLayout.reset(errorLabel, result, amountField, rateField, termField);
            split.hide();
            unitCombo.setValue(UNIT_YEARS);
            rateField.setText(AppSettings.loanRateText());   // back to the defaults from Settings
            termField.setText(AppSettings.loanYearsText());
        });

        // Send to calculator: fills the inputs from a saved line
        HistoryLoader.register("Business - Loan Repayment", record -> {
            var m = HistoryLoader.find(record.expression,
                    "Loan R([\\d.,]+) @ ([\\d.,]+)% over ([\\d.,]+) (yrs|months|days)");
            if (m == null) {
                return "This saved line could not be read.";
            }
            amountField.setText(m.group(1));
            rateField.setText(m.group(2));
            unitCombo.setValue(unitFromWord(m.group(4)));
            termField.setText(m.group(3));
            return null;
        });

        return CalcLayout.page("Savings & Loans", "Loan & Bond Repayment Calculator", errorLabel, null,
                CalcLayout.inputsCard(
                        CalcLayout.field(CalcLayout.label("Loan Amount (R):"), amountField),
                        CalcLayout.field(CalcLayout.label("Annual Interest Rate (%):"), rateField),
                        CalcLayout.field(termLabel, termField),
                        CalcLayout.field(CalcLayout.label("Term Unit:"), unitCombo),
                        CalcLayout.buttonRow(calcBtn, resetBtn)),
                new VBox(16, result.root, split.card));
    }
}
