package application;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Pure money, tax and pricing formulas. No JavaFX here, so every rule can be tested on its own.
 *
 * Money in the pricing, VAT, merchant-fee and loan rules is BigDecimal, rounded to cents.
 * Tax brackets and the freelance search use double because they are iterative; only the
 * final amounts are rounded to cents for display.
 */
public final class FinanceMath {

    /** Days in a year used when a term is given in days. */
    public static final double DAYS_PER_YEAR = 365.0;

    /** Section 11F: retirement contributions may reduce taxable income by 27.5% of income... */
    public static final double S11F_PERCENT = 0.275;

    /** ...up to this rand cap per year. */
    public static final double S11F_CAP = 350_000;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal ONE_CENT = new BigDecimal("0.01");
    private static final MathContext MC = new MathContext(34, RoundingMode.HALF_UP);

    private FinanceMath() {}

    // ------------------------------------------------------------ rounding

    /** Rounds to the nearest cent, halves up. */
    public static BigDecimal toCents(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    /** Rounds up to the next cent, so a price or charge is never short of what it must cover. */
    public static BigDecimal toCentsUp(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.CEILING);
    }

    // ------------------------------------------------------------ time

    /** Converts a term given in years or days into years. */
    public static double termInYears(double value, boolean inDays) {
        return inDays ? value / DAYS_PER_YEAR : value;
    }

    /** Number of whole monthly payments in a term given in years. */
    public static int monthsIn(double years) {
        return (int) Math.round(years * 12);
    }

    // ------------------------------------------------------------ VAT

    /** Removes VAT from a VAT-inclusive amount: incl / (1 + rate), rounded to cents. */
    public static BigDecimal exclusiveFromInclusive(BigDecimal inclusive, BigDecimal vatRate) {
        return inclusive.divide(BigDecimal.ONE.add(vatRate), 2, RoundingMode.HALF_UP);
    }

    /** Adds VAT to a VAT-exclusive amount: excl x (1 + rate), rounded to cents. */
    public static BigDecimal inclusiveFromExclusive(BigDecimal exclusive, BigDecimal vatRate) {
        return exclusive.multiply(BigDecimal.ONE.add(vatRate)).setScale(2, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------ loans

    /**
     * Monthly instalment in rand, to the cent: P x r / (1 - (1 + r)^-n), with r = annual rate / 12.
     * A 0% loan is split evenly. The loan needs at least one monthly payment.
     */
    public static BigDecimal loanInstalment(BigDecimal principal, BigDecimal annualRatePct, int months) {
        if (months < 1) {
            throw new IllegalArgumentException("A loan needs at least one monthly payment.");
        }
        if (annualRatePct.signum() == 0) {
            return principal.divide(BigDecimal.valueOf(months), 2, RoundingMode.HALF_UP);
        }
        double r = annualRatePct.doubleValue() / 100.0 / 12.0;
        double payment = principal.doubleValue() * r / (1 - Math.pow(1 + r, -months));
        if (!Double.isFinite(payment)) {
            throw new IllegalArgumentException("The instalment is too large to calculate.");
        }
        return BigDecimal.valueOf(payment).setScale(2, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------ margin and markup

    /** Cost, selling price, and the profit, margin and markup they give. Markup is null when cost is zero. */
    public static final class Pricing {
        public final BigDecimal cost;
        public final BigDecimal sellingPrice;
        public final BigDecimal profit;
        public final BigDecimal marginPct;
        public final BigDecimal markupPct;

        Pricing(BigDecimal cost, BigDecimal sellingPrice, BigDecimal profit,
                BigDecimal marginPct, BigDecimal markupPct) {
            this.cost = cost;
            this.sellingPrice = sellingPrice;
            this.profit = profit;
            this.marginPct = marginPct;
            this.markupPct = markupPct;
        }
    }

    /** Cost and selling price in: profit, margin % (of price) and markup % (of cost). */
    public static Pricing fromPrice(BigDecimal cost, BigDecimal sellingPrice)
            throws InputParser.InvalidInputException {
        if (sellingPrice.signum() <= 0) {
            throw new InputParser.InvalidInputException("Selling price must be more than zero.");
        }
        BigDecimal profit = sellingPrice.subtract(cost);
        BigDecimal margin = profit.multiply(HUNDRED).divide(sellingPrice, MC);
        BigDecimal markup = cost.signum() == 0 ? null : profit.multiply(HUNDRED).divide(cost, MC);
        return new Pricing(cost, sellingPrice, profit, margin, markup);
    }

    /**
     * The selling price that gives the target margin, rounded UP to the cent so the margin
     * reached is never below the target.
     */
    public static Pricing fromTargetMargin(BigDecimal cost, BigDecimal targetPct)
            throws InputParser.InvalidInputException {
        if (targetPct.signum() < 0 || targetPct.compareTo(HUNDRED) >= 0) {
            throw new InputParser.InvalidInputException(
                    "Target margin must be from 0 up to (but not including) 100%.");
        }
        requireCostForPrice(cost);
        BigDecimal factor = BigDecimal.ONE.subtract(targetPct.divide(HUNDRED, MC));
        return fromPrice(cost, toCentsUp(cost.divide(factor, MC)));
    }

    /** The selling price that adds the target markup to cost, rounded up to the cent. */
    public static Pricing fromTargetMarkup(BigDecimal cost, BigDecimal targetPct)
            throws InputParser.InvalidInputException {
        if (targetPct.signum() < 0) {
            throw new InputParser.InvalidInputException("Target markup cannot be negative.");
        }
        requireCostForPrice(cost);
        BigDecimal factor = BigDecimal.ONE.add(targetPct.divide(HUNDRED, MC));
        return fromPrice(cost, toCentsUp(cost.multiply(factor, MC)));
    }

    private static void requireCostForPrice(BigDecimal cost) throws InputParser.InvalidInputException {
        if (cost.signum() <= 0) {
            throw new InputParser.InvalidInputException(
                    "Cost price must be more than zero to work out a selling price.");
        }
    }

    // ------------------------------------------------------------ merchant fees

    /** A merchant fee breakdown in rand. Every figure is to the cent. */
    public static final class Fees {
        public final BigDecimal amount;
        public final BigDecimal percentFee;
        public final BigDecimal fixedFee;
        public final BigDecimal vat;
        public final BigDecimal total;
        public final BigDecimal net;

        Fees(BigDecimal amount, BigDecimal percentFee, BigDecimal fixedFee,
             BigDecimal vat, BigDecimal total, BigDecimal net) {
            this.amount = amount;
            this.percentFee = percentFee;
            this.fixedFee = fixedFee;
            this.vat = vat;
            this.total = total;
            this.net = net;
        }
    }

    /**
     * True when the fee leaves room for a charge: the percentage fee plus VAT on it must be
     * under 100%, or the charge would have no finite value.
     */
    public static boolean canCharge(BigDecimal feeRate, BigDecimal vatRate) {
        return BigDecimal.ONE.subtract(feeRate.multiply(BigDecimal.ONE.add(vatRate))).signum() > 0;
    }

    /** Fees taken from an amount the customer pays. feeRate is a fraction, e.g. 0.035 for 3.5%. */
    public static Fees forwardFees(BigDecimal amount, BigDecimal feeRate, BigDecimal fixedFee,
                                   BigDecimal vatRate) {
        BigDecimal percentFee = toCents(amount.multiply(feeRate));
        BigDecimal feesBeforeVat = percentFee.add(fixedFee);
        BigDecimal vat = toCents(feesBeforeVat.multiply(vatRate));
        BigDecimal total = feesBeforeVat.add(vat);
        return new Fees(amount, percentFee, fixedFee, vat, total, amount.subtract(total));
    }

    /**
     * The smallest charge, to the cent, that leaves the merchant at least the target payout.
     * Each fee is rounded to the cent, so the charge is nudged up a cent at a time if needed.
     * Callers must check canCharge first.
     */
    public static Fees chargeForPayout(BigDecimal target, BigDecimal feeRate, BigDecimal fixedFee,
                                       BigDecimal vatRate) {
        if (!canCharge(feeRate, vatRate)) {
            throw new IllegalArgumentException("The fee rate is too high to calculate a charge.");
        }
        BigDecimal vatFactor = BigDecimal.ONE.add(vatRate);
        BigDecimal denominator = BigDecimal.ONE.subtract(feeRate.multiply(vatFactor));
        BigDecimal exactCharge = target.add(fixedFee.multiply(vatFactor)).divide(denominator, MC);

        BigDecimal charge = toCentsUp(exactCharge);
        Fees fees = forwardFees(charge, feeRate, fixedFee, vatRate);
        while (fees.net.compareTo(target) < 0) {
            charge = charge.add(ONE_CENT);
            fees = forwardFees(charge, feeRate, fixedFee, vatRate);
        }
        // Fees are rounded to the cent, so a charge one cent lower can still leave the target. Take the lowest one that does.
        while (charge.subtract(ONE_CENT).signum() > 0) {
            BigDecimal lower = charge.subtract(ONE_CENT);
            Fees lowerFees = forwardFees(lower, feeRate, fixedFee, vatRate);
            if (lowerFees.net.compareTo(target) < 0) {
                break;
            }
            charge = lower;
            fees = lowerFees;
        }
        return fees;
    }

    // ------------------------------------------------------------ income tax (2026/27)

    /** Annual income tax after rebates. Zero at or below the age group's tax threshold. */
    public static double annualTax(double annualIncome, String ageGroup) {
        if (annualIncome <= AppSettings.getThreshold(ageGroup)) {
            return 0.0;
        }
        return Math.max(0.0, AppSettings.calculateTaxBracket(annualIncome) - AppSettings.getRebate(ageGroup));
    }

    /**
     * The rebate actually used: the full rebate, but never more than the tax before rebates.
     * Showing more than this would make the rebate look bigger than the tax it reduces.
     */
    public static double rebateApplied(double annualIncome, String ageGroup) {
        double taxBeforeRebate = Math.max(0, AppSettings.calculateTaxBracket(annualIncome));
        return Math.min(taxBeforeRebate, AppSettings.getRebate(ageGroup));
    }

    /**
     * Gross annual income that leaves the wanted net annual income after PAYE. Net (gross minus tax)
     * rises steadily with gross, so bisection converges safely.
     */
    public static double grossForNet(double targetNetAnnual, String ageGroup) {
        double low = 0;
        double high = Math.max(1.0, targetNetAnnual) * 3 + 1_000_000;
        for (int i = 0; i < 200; i++) {
            double mid = (low + high) / 2;
            double net = mid - annualTax(mid, ageGroup);
            if (net < targetNetAnnual) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return (low + high) / 2;
    }

    // ------------------------------------------------------------ section 11F

    /** The most that can be deducted in a year: the lowest of 27.5% of income and R350 000. */
    public static double section11fLimit(double income) {
        return Math.max(0, Math.min(S11F_CAP, S11F_PERCENT * income));
    }

    /**
     * Section 11F deduction: the lowest of the contributions made, 27.5% of income, and R350 000.
     * Any contributions above that are not deductible this year.
     */
    public static double section11fDeduction(double contributions, double income) {
        return Math.max(0, Math.min(contributions, section11fLimit(income)));
    }

    // ------------------------------------------------------------ medical scheme tax credit (section 6A, 2026/27)

    /** Monthly credit for the main member and for the first dependant, 2026/27. */
    public static final double MEDICAL_FIRST_TWO_MONTHLY = 376;

    /** Monthly credit for each further dependant, 2026/27. */
    public static final double MEDICAL_EACH_ADDITIONAL_MONTHLY = 254;

    /** Days in an average month (365.25 / 12), used when a loan term is given in days. */
    public static final double DAYS_PER_MONTH = 365.25 / 12;

    /**
     * The medical scheme fees tax credit for a year. The main member and the first dependant each earn the first
     * credit, and every further dependant earns the lower one. The count includes the main member, so 0 means no scheme.
     */
    public static double medicalCreditAnnual(int members) {
        int count = Math.max(0, members);
        double monthly = MEDICAL_FIRST_TWO_MONTHLY * Math.min(count, 2)
                + MEDICAL_EACH_ADDITIONAL_MONTHLY * Math.max(0, count - 2);
        return monthly * 12;
    }

    // ------------------------------------------------------------ PAYE for a year

    /** The PAYE figures for a year, in rand. Pure, so the rules can be tested without JavaFX. */
    public static final class Paye {
        /** Gross income for the year. */
        public final double gross;
        /** Retirement fund contributions paid for the year, in full. */
        public final double contributions;
        /** Section 11F deduction: the part of the contributions that reduces taxable income. */
        public final double deduction;
        /** Tax on the taxable income, before the rebate. */
        public final double taxBeforeRebate;
        /** The rebate used: never more than the tax before rebate. */
        public final double rebate;
        /** The medical scheme credit used: never more than the tax after rebate. */
        public final double medicalCredit;
        /** Tax payable for the year after the medical credits. Never below zero. */
        public final double netTax;
        /** Section 6B additional medical expenses credit used: never more than the tax left after section 6A. */
        public final double medicalExpensesCredit;
        /** UIF employee contribution for the year. It does not change tax; it reduces take-home pay. */
        public final double uif;

        Paye(double gross, double contributions, double deduction, double taxBeforeRebate, double rebate,
             double medicalCredit, double medicalExpensesCredit, double netTax, double uif) {
            this.gross = gross;
            this.contributions = contributions;
            this.deduction = deduction;
            this.taxBeforeRebate = taxBeforeRebate;
            this.rebate = rebate;
            this.medicalCredit = medicalCredit;
            this.medicalExpensesCredit = medicalExpensesCredit;
            this.netTax = netTax;
            this.uif = uif;
        }

        /**
         * Tax deducted on the payslip for the year: after the 6A credit. Section 6B is claimed at year-end
         * on the ITR12, so it is left out here.
         */
        public double payslipTax() {
            return netTax + medicalExpensesCredit;
        }
    }

    /** The age group that gets the lower section 6B rate, unless a disabled family member is included. */
    static final String UNDER_65 = "Under 65";

    /** Section 6B rates and limits, 2026/27. */
    public static final double MEDICAL_EXPENSES_RATE = 0.25;
    public static final double MEDICAL_EXPENSES_RATE_ENHANCED = 0.333;
    public static final double MEDICAL_EXPENSES_TAXABLE_LIMIT = 0.075;

    /**
     * Section 6B additional medical expenses credit for a year, from what was paid: scheme contributions and
     * out-of-pocket expenses. Under 65 with no disabled family member: 25% of the amount above four times the
     * section 6A credit and 7.5% of taxable income. Otherwise: 33.3% of the amount above three times the 6A credit.
     */
    public static double medicalExpensesCredit(int members, double annualSchemeContributions,
                                               double annualOutOfPocket, double taxableIncome, boolean enhanced) {
        double sixA = medicalCreditAnnual(members);
        double paid = Math.max(0, annualSchemeContributions) + Math.max(0, annualOutOfPocket);
        if (enhanced) {
            return Math.max(0, MEDICAL_EXPENSES_RATE_ENHANCED * (paid - 3 * sixA));
        }
        double threshold = 4 * sixA + MEDICAL_EXPENSES_TAXABLE_LIMIT * Math.max(0, taxableIncome);
        return Math.max(0, MEDICAL_EXPENSES_RATE * (paid - threshold));
    }

    /** UIF employee contribution rate: 1% of remuneration. */
    public static final double UIF_RATE = 0.01;

    /** UIF monthly remuneration ceiling, 2026/27: R17 712. Pay above it does not attract UIF. */
    public static final double UIF_MONTHLY_CEILING = 17_712;

    /** UIF employee contribution for a year: 1% of each month's pay, with pay above the ceiling not counted. */
    public static double uifAnnual(double annualGross) {
        double monthly = Math.max(0, annualGross) / 12;
        return UIF_RATE * Math.min(monthly, UIF_MONTHLY_CEILING) * 12;
    }

    /** Annual PAYE with the medical scheme credit only. Retirement, section 6B and UIF are left out. */
    public static Paye paye(double annualGross, double annualContributions, int medicalMembers, String ageGroup) {
        return paye(annualGross, annualContributions, medicalMembers, ageGroup, 0, 0, false);
    }

    /**
     * Annual PAYE with every figure the SARS PAYE tool shows. Contributions reduce taxable income up to the section
     * 11F limit, the age group's rebate follows, then the section 6A and 6B credits come off the tax that is left.
     * Neither credit can take tax below zero. UIF does not change tax; it is worked out for the take-home pay.
     */
    public static Paye paye(double annualGross, double annualContributions, int medicalMembers, String ageGroup,
                            double annualSchemeContributions, double annualOutOfPocket, boolean disabled) {
        double gross = Math.max(0, annualGross);
        double contributions = Math.max(0, annualContributions);
        double deduction = section11fDeduction(contributions, gross);
        double taxable = gross - deduction;
        double taxBeforeRebate = Math.max(0, AppSettings.calculateTaxBracket(taxable));
        double rebate = rebateApplied(taxable, ageGroup);
        double taxAfterRebate = annualTax(taxable, ageGroup);
        double medical = Math.min(medicalCreditAnnual(medicalMembers), taxAfterRebate);
        boolean enhanced = !UNDER_65.equals(ageGroup) || disabled;
        double expenses = Math.min(medicalExpensesCredit(medicalMembers, annualSchemeContributions,
                annualOutOfPocket, taxable, enhanced), taxAfterRebate - medical);
        return new Paye(gross, contributions, deduction, taxBeforeRebate, rebate, medical, expenses,
                taxAfterRebate - medical - expenses, uifAnnual(gross));
    }
}
