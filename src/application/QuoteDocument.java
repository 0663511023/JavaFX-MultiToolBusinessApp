package application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * The content of a quotation or invoice: who it is for, what is being charged and the VAT on it.
 * Amounts are exact decimals. Unit prices are before VAT, and VAT is added on the subtotal.
 * It holds only what the client sees. It has no fields for cost, margin, markup or tax workings,
 * so those cannot be printed on a quote or invoice.
 * Pure logic with no JavaFX, so it can be tested on its own.
 */
public final class QuoteDocument {

    public enum Kind { QUOTE, INVOICE }

    /** One line of the document: a description, a quantity and the unit price before VAT. */
    public static final class Item {
        public final String description;
        public final BigDecimal quantity;
        public final BigDecimal unitPrice;

        public Item(String description, BigDecimal quantity, BigDecimal unitPrice) {
            if (quantity.signum() <= 0) {
                throw new IllegalArgumentException("Quantity must be more than zero.");
            }
            if (unitPrice.signum() < 0) {
                throw new IllegalArgumentException("Unit price cannot be negative.");
            }
            this.description = description;
            this.quantity = quantity;
            this.unitPrice = unitPrice;
        }

        /** Quantity times unit price, to the cent. */
        public BigDecimal amount() {
            return quantity.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP);
        }
    }

    /** The subtotal before VAT, the VAT on it, and the total including VAT. */
    public static final class Totals {
        public final BigDecimal subtotal;
        public final BigDecimal vat;
        public final BigDecimal total;

        Totals(BigDecimal subtotal, BigDecimal vat, BigDecimal total) {
            this.subtotal = subtotal;
            this.vat = vat;
            this.total = total;
        }
    }

    public final Kind kind;
    public final String number;
    public final LocalDate date;
    public final LocalDate validUntil;
    public final String clientName;
    public final ContactDetails clientContact;
    public final List<Item> items;
    /** The VAT rate as a fraction, such as 0.15. Zero when no VAT is charged. */
    public final BigDecimal vatRate;
    public final String notes;

    public QuoteDocument(Kind kind, String number, LocalDate date, LocalDate validUntil, String clientName,
                         ContactDetails clientContact, List<Item> items, BigDecimal vatRate, String notes) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("A quote needs at least one item.");
        }
        if (vatRate == null || vatRate.signum() < 0) {
            throw new IllegalArgumentException("The VAT rate cannot be negative.");
        }
        this.kind = kind;
        this.number = number;
        this.date = date;
        this.validUntil = validUntil;
        this.clientName = clientName == null ? "" : clientName.trim();
        this.clientContact = clientContact == null ? ContactDetails.EMPTY : clientContact;
        this.items = List.copyOf(items);
        this.vatRate = vatRate;
        this.notes = notes == null ? "" : notes.trim();
    }

    /** The subtotal of all items, the VAT on it (to the cent), and the total. */
    public Totals totals() {
        BigDecimal subtotal = BigDecimal.ZERO;
        for (Item item : items) {
            subtotal = subtotal.add(item.amount());
        }
        BigDecimal vat = subtotal.multiply(vatRate).setScale(2, RoundingMode.HALF_UP);
        return new Totals(subtotal, vat, subtotal.add(vat));
    }

    /** A price that includes VAT, converted to the price before VAT, to the cent. */
    public static BigDecimal exclusiveFrom(BigDecimal inclusive, BigDecimal vatRate) {
        return inclusive.divide(BigDecimal.ONE.add(vatRate), 2, RoundingMode.HALF_UP);
    }

    /** The heading on the document. A VAT charge makes it a tax invoice. */
    public String title() {
        if (kind == Kind.QUOTE) {
            return "QUOTATION";
        }
        return vatRate.signum() > 0 ? "TAX INVOICE" : "INVOICE";
    }

    /** The label for the date at the right of the document. */
    public String dateLabel() {
        return kind == Kind.QUOTE ? "Valid until" : "Due date";
    }
}
