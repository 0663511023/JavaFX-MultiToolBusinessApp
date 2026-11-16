package application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * What-if figures built on FinanceMath: a sensitivity table that shows how profit moves when the cost changes,
 * and the figures for two pricing scenarios side by side. Pure logic with no JavaFX.
 */
public final class Scenarios {

    /** The cost changes the sensitivity table shows, in percent. 0 is the cost as entered. */
    public static final int[] COST_CHANGES = {-5, 0, 5};

    private Scenarios() {
    }

    /** One line of the sensitivity table: the change, the cost it gives, and the figures at the same selling price. */
    public static final class Row {
        public final int changePct;
        public final BigDecimal cost;
        public final FinanceMath.Pricing pricing;

        Row(int changePct, BigDecimal cost, FinanceMath.Pricing pricing) {
            this.changePct = changePct;
            this.cost = cost;
            this.pricing = pricing;
        }
    }

    /**
     * How profit changes when the cost moves by each of COST_CHANGES, with the selling price held where it is.
     * The cost is rounded to the cent, as it would be on an invoice.
     */
    public static List<Row> sensitivity(BigDecimal cost, BigDecimal sellingPrice)
            throws InputParser.InvalidInputException {
        List<Row> rows = new ArrayList<>();
        for (int change : COST_CHANGES) {
            BigDecimal factor = BigDecimal.valueOf(100 + change).divide(BigDecimal.valueOf(100));
            BigDecimal changed = cost.multiply(factor).setScale(2, RoundingMode.HALF_UP);
            rows.add(new Row(change, changed, FinanceMath.fromPrice(changed, sellingPrice)));
        }
        return rows;
    }

    /**
     * One pricing scenario: the cost, and either a selling price (byMarkup false) or a target markup percentage
     * (byMarkup true) that sets the price.
     */
    public static FinanceMath.Pricing scenario(BigDecimal cost, boolean byMarkup, BigDecimal input)
            throws InputParser.InvalidInputException {
        return byMarkup ? FinanceMath.fromTargetMarkup(cost, input) : FinanceMath.fromPrice(cost, input);
    }

    /** B minus A for profit per unit, in rand. */
    public static BigDecimal profitDifference(FinanceMath.Pricing a, FinanceMath.Pricing b) {
        return b.profit.subtract(a.profit);
    }

    /** B minus A for the margin, in percentage points. */
    public static BigDecimal marginDifference(FinanceMath.Pricing a, FinanceMath.Pricing b) {
        return b.marginPct.subtract(a.marginPct);
    }
}
