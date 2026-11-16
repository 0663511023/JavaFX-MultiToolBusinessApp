package application;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lays out a quotation or invoice on A4 pages, with the business branding at the top and the footer note
 * at the bottom. Amounts use the number style chosen in Settings. Email addresses are clickable links.
 * Pure logic with no JavaFX.
 */
final class QuotePdf {

    private static final float LEFT = 40;
    private static final float RIGHT = 555;
    private static final float DESC_X = 48;
    private static final float DESC_WIDTH = 280;
    private static final float QTY_RIGHT = 380;
    private static final float UNIT_RIGHT = 462;
    private static final float AMOUNT_RIGHT = 547;
    private static final float LINE_HEIGHT = 13;
    /** Nothing is printed below this height, so the footer stays clear. */
    private static final float LOWEST_LINE = 96;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private QuotePdf() {}

    /** The whole document as a PDF, ready to write to a file. */
    static PdfDocument build(QuoteDocument q, Branding b) {
        PdfDocument pdf = new PdfDocument();
        int logo = b.addLogoTo(pdf);
        PdfDocument.Page page = pdf.addPage();
        float y = drawFirstHeader(pdf, page, b, logo);

        // Title, and the number and dates on the right
        page.text(q.title(), LEFT, y, 22, true, PdfDocument.EMERALD);
        page.textRight("No: " + q.number, RIGHT, y, 10, true, PdfDocument.BLACK);
        page.textRight("Date: " + DATE.format(q.date), RIGHT, y - 14, 10, false, PdfDocument.BLACK);
        page.textRight(q.dateLabel() + ": " + DATE.format(q.validUntil), RIGHT, y - 28, 10, false, PdfDocument.BLACK);
        y -= 58;

        // Who it is for: the name, then each contact detail on its own line
        page.text("BILL TO", LEFT, y, 8, true, PdfDocument.GREY);
        y -= 14;
        page.text(q.clientName.isEmpty() ? "Client" : q.clientName, LEFT, y, 11, true);
        y -= 14;
        for (String line : contactLines(q.clientContact, "VAT no. ", 300)) {
            page.textWithLinks(line, LEFT, y, 9, false, PdfDocument.BLACK);
            y -= 12;
        }
        y -= 20;

        // If the client block ran far down the page, the items start on a fresh page
        if (y < 240) {
            page = pdf.addPage();
            y = continuationHeader(page, b);
        }

        // The items
        y = tableHeader(page, y);
        for (QuoteDocument.Item item : q.items) {
            List<String> description = PdfDocument.wrap(item.description, DESC_WIDTH, 10, false);
            float rowHeight = Math.max(1, description.size()) * LINE_HEIGHT + 8;
            if (y - rowHeight < LOWEST_LINE) {
                page = pdf.addPage();
                y = continuationHeader(page, b);
                y = tableHeader(page, y);
            }
            float top = y;
            for (int i = 0; i < description.size(); i++) {
                page.textWithLinks(description.get(i), DESC_X, top - 12 - i * LINE_HEIGHT, 10, false, PdfDocument.BLACK);
            }
            float firstLine = top - 12;
            page.textRight(Money.text(plain(item.quantity)), QTY_RIGHT, firstLine, 10, false, PdfDocument.BLACK);
            page.textRight(Money.amount(item.unitPrice), UNIT_RIGHT, firstLine, 10, false, PdfDocument.BLACK);
            page.textRight(Money.amount(item.amount()), AMOUNT_RIGHT, firstLine, 10, false, PdfDocument.BLACK);
            y = top - rowHeight;
            page.line(LEFT, y, RIGHT, y, 0.5f, PdfDocument.RULE);
        }

        // The totals, kept together on one page
        QuoteDocument.Totals totals = q.totals();
        boolean vat = q.vatRate.signum() > 0;
        if (y < 200) {
            page = pdf.addPage();
            y = continuationHeader(page, b);
        }
        y -= 10;
        page.textRight(vat ? "Subtotal (excl. VAT)" : "Subtotal", UNIT_RIGHT, y, 10, false, PdfDocument.BLACK);
        page.textRight(Money.amount(totals.subtotal), AMOUNT_RIGHT, y, 10, false, PdfDocument.BLACK);
        y -= 16;
        if (vat) {
            String percent = Money.text(q.vatRate.movePointRight(2).stripTrailingZeros().toPlainString());
            page.textRight("VAT (" + percent + "%)", UNIT_RIGHT, y, 10, false, PdfDocument.BLACK);
            page.textRight(Money.amount(totals.vat), AMOUNT_RIGHT, y, 10, false, PdfDocument.BLACK);
            y -= 16;
        }
        page.line(340, y + 11, RIGHT, y + 11, 0.8f, PdfDocument.GREY);
        page.textRight(vat ? "Total (incl. VAT)" : "Total", UNIT_RIGHT, y, 11, true, PdfDocument.BLACK);
        page.textRight(Money.amount(totals.total), AMOUNT_RIGHT, y, 11, true, PdfDocument.BLACK);
        y -= 26;

        // Notes, if any
        if (!q.notes.isEmpty()) {
            if (y < LOWEST_LINE + 40) {
                page = pdf.addPage();
                y = continuationHeader(page, b);
            }
            page.text("NOTES", LEFT, y, 8, true, PdfDocument.GREY);
            y -= 13;
            for (String line : PdfDocument.wrap(q.notes, RIGHT - LEFT, 9, false)) {
                if (y < LOWEST_LINE) {
                    page = pdf.addPage();
                    y = continuationHeader(page, b);
                }
                page.textWithLinks(line, LEFT, y, 9, false, PdfDocument.BLACK);
                y -= 12;
            }
        }

        pdf.addFooters(b.footerNote());
        return pdf;
    }

    /**
     * The contact lines, wrapped to the width, with the VAT number last. Each email address is kept as its
     * own text, so it can be highlighted as a link.
     */
    private static List<String> contactLines(ContactDetails c, String vatLabel, float width) {
        List<String> lines = new ArrayList<>();
        for (String line : c.printLines()) {
            lines.addAll(PdfDocument.wrap(line, width, 9, false));
        }
        if (c.hasVatNumber()) {
            lines.add(vatLabel + c.vatNumber);
        }
        return lines;
    }

    /** The full header on the first page: the logo at the top right, then the business details. */
    private static float drawFirstHeader(PdfDocument pdf, PdfDocument.Page page, Branding b, int logo) {
        float top = 800;
        float logoBottom = top;
        if (logo >= 0) {
            float[] size = PdfDocument.fit(pdf.imageWidth(logo), pdf.imageHeight(logo), 150, 70);
            page.image(logo, RIGHT - size[0], top - size[1], size[0], size[1]);
            logoBottom = top - size[1];
        }
        float y = top - 16;
        for (String line : PdfDocument.wrap(b.businessName(), 380, 16, true)) {
            page.text(line, LEFT, y, 16, true);
            y -= 19;
        }
        for (String line : contactLines(b.contact(), "VAT reg. no. ", 380)) {
            page.textWithLinks(line, LEFT, y, 9, false, PdfDocument.BLACK);
            y -= 12;
        }
        float rule = Math.min(logoBottom, y + 12) - 12;
        page.line(LEFT, rule, RIGHT, rule, 0.8f, PdfDocument.RULE);
        return rule - 26;
    }

    /** A slim header for the pages after the first. */
    private static float continuationHeader(PdfDocument.Page page, Branding b) {
        float top = 800;
        if (!b.businessName().isEmpty()) {
            page.text(b.businessName(), LEFT, top - 10, 10, true, PdfDocument.GREY);
        }
        page.line(LEFT, top - 18, RIGHT, top - 18, 0.8f, PdfDocument.RULE);
        return top - 36;
    }

    /** The column headings, on a tinted band. Returns the height where the first row starts. */
    private static float tableHeader(PdfDocument.Page page, float y) {
        page.fillRect(LEFT, y - 16, RIGHT - LEFT, 20, PdfDocument.TINT);
        page.text("Description", DESC_X, y - 10, 9, true);
        page.textRight("Qty", QTY_RIGHT, y - 10, 9, true, PdfDocument.BLACK);
        page.textRight("Unit price", UNIT_RIGHT, y - 10, 9, true, PdfDocument.BLACK);
        page.textRight("Amount", AMOUNT_RIGHT, y - 10, 9, true, PdfDocument.BLACK);
        return y - 24;
    }

    /** A number without trailing zeros, such as 1 or 2.5, as plain text. */
    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
