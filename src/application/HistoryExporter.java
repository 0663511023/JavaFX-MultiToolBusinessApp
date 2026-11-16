package application;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Exports calculation history to CSV, Excel (.xlsx) or PDF.
 * Written with only the Java standard library, so no extra jars are needed.
 * Pure logic with no JavaFX.
 */
public final class HistoryExporter {

    public enum Format {
        PDF("pdf", "PDF document"),
        XLSX("xlsx", "Excel workbook"),
        CSV("csv", "CSV file");

        public final String extension;
        public final String description;

        Format(String extension, String description) {
            this.extension = extension;
            this.description = description;
        }
    }

    static final String[] HEADERS = {"Date", "Time", "Calculator", "Expression", "Result"};
    private static final int[] COLUMN_WIDTHS = {12, 10, 26, 44, 34};

    // PDF page layout (A4, in points). The header is drawn by Branding, so the body starts below it.
    private static final float MARGIN_X = 40;
    private static final float BOTTOM_Y = 75;
    private static final float PDF_WRAP_WIDTH = 510;

    private HistoryExporter() {}

    public static void write(Format format, Path path, List<HistoryRecord> records) throws IOException {
        switch (format) {
            case PDF -> writePdf(path, records);
            case XLSX -> writeXlsx(path, records);
            case CSV -> writeCsv(path, records);
            default -> throw new IllegalArgumentException("Unknown format: " + format);
        }
    }

    private static String[] cells(HistoryRecord r) {
        return new String[]{r.dateText(), r.time, r.calculator, Money.text(r.expression), Money.text(r.result)};
    }

    // ---------------------------------------------------------------- CSV

    public static void writeCsv(Path path, List<HistoryRecord> records) throws IOException {
        try (var writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write('﻿'); // byte-order mark, so Excel shows symbols like R and ÷ correctly
            writer.write(csvLine(HEADERS));
            writer.write("\r\n");
            for (HistoryRecord r : records) {
                writer.write(csvLine(cells(r)));
                writer.write("\r\n");
            }
        }
    }

    static String csvLine(String[] fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(csvField(fields[i]));
        }
        return sb.toString();
    }

    static String csvField(String raw) {
        // A cell that starts like a formula would run when opened in Excel, so it is kept as plain text
        String value = !raw.isEmpty() && "=+-@\t\r".indexOf(raw.charAt(0)) >= 0 ? "'" + raw : raw;
        if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    // ---------------------------------------------------------------- Excel

    public static void writeXlsx(Path path, List<HistoryRecord> records) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            putEntry(zip, "[Content_Types].xml", CONTENT_TYPES);
            putEntry(zip, "_rels/.rels", ROOT_RELS);
            putEntry(zip, "xl/workbook.xml", WORKBOOK);
            putEntry(zip, "xl/_rels/workbook.xml.rels", WORKBOOK_RELS);
            putEntry(zip, "xl/styles.xml", STYLES);
            putEntry(zip, "xl/worksheets/sheet1.xml", sheetXml(records));
        }
    }

    private static void putEntry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    static String sheetXml(List<HistoryRecord> records) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
        sb.append("<cols>");
        for (int i = 0; i < COLUMN_WIDTHS.length; i++) {
            sb.append("<col min=\"").append(i + 1).append("\" max=\"").append(i + 1)
              .append("\" width=\"").append(COLUMN_WIDTHS[i]).append("\" customWidth=\"1\"/>");
        }
        sb.append("</cols><sheetData>");
        appendRow(sb, 1, HEADERS, 1); // header row in bold
        int row = 2;
        for (HistoryRecord r : records) {
            appendRow(sb, row++, cells(r), 0);
        }
        sb.append("</sheetData></worksheet>");
        return sb.toString();
    }

    private static void appendRow(StringBuilder sb, int rowNumber, String[] values, int style) {
        sb.append("<row r=\"").append(rowNumber).append("\">");
        for (int c = 0; c < values.length; c++) {
            String ref = String.valueOf((char) ('A' + c)) + rowNumber;
            sb.append("<c r=\"").append(ref).append("\" t=\"inlineStr\" s=\"").append(style).append("\">")
              .append("<is><t xml:space=\"preserve\">").append(xmlEscape(values[c])).append("</t></is></c>");
        }
        sb.append("</row>");
    }

    static String xmlEscape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&apos;");
                default -> {
                    // XML cannot hold most control characters, so drop them
                    if (ch >= 0x20 || ch == '\t' || ch == '\n' || ch == '\r') {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static final String CONTENT_TYPES =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
            + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
            + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
            + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
            + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
            + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
            + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>"
            + "</Types>";

    private static final String ROOT_RELS =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
            + "</Relationships>";

    private static final String WORKBOOK =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
            + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\""
            + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
            + "<sheets><sheet name=\"History\" sheetId=\"1\" r:id=\"rId1\"/></sheets>"
            + "</workbook>";

    private static final String WORKBOOK_RELS =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
            + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
            + "</Relationships>";

    private static final String STYLES =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
            + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
            + "<fonts count=\"2\">"
            + "<font><sz val=\"11\"/><name val=\"Calibri\"/></font>"
            + "<font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font>"
            + "</fonts>"
            + "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>"
            + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
            + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
            + "<cellXfs count=\"2\">"
            + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
            + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>"
            + "</cellXfs>"
            + "</styleSheet>";

    // ---------------------------------------------------------------- PDF

    /** One printed line: its font, size and text. */
    private static final class PdfLine {
        final boolean bold;
        final float size;
        final String text;

        PdfLine(boolean bold, float size, String text) {
            this.bold = bold;
            this.size = size;
            this.text = text;
        }

        float height() {
            return size * 1.6f;
        }
    }

    /** Writes the history as a PDF, branded with the Settings details. */
    public static void writePdf(Path path, List<HistoryRecord> records) throws IOException {
        Files.write(path, buildPdf(records, Branding.current()));
    }

    static List<PdfLine> buildLines(List<HistoryRecord> records) {
        List<PdfLine> lines = new ArrayList<>();
        lines.add(new PdfLine(true, 16, "Calculation History"));
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        lines.add(new PdfLine(false, 9, "Exported " + stamp + "  |  " + records.size() + " entries"));
        lines.add(new PdfLine(false, 9, ""));

        if (records.isEmpty()) {
            lines.add(new PdfLine(false, 10, "No calculations to export yet."));
            return lines;
        }

        String currentDay = null;
        for (HistoryRecord r : records) {
            String day = r.dayLabel();
            if (!day.equals(currentDay)) {
                if (currentDay != null) {
                    lines.add(new PdfLine(false, 9, ""));
                }
                lines.add(new PdfLine(true, 11, day));
                currentDay = day;
            }
            for (String wrapped : PdfDocument.wrap(r.displayLine(), PDF_WRAP_WIDTH, 9, false)) {
                lines.add(new PdfLine(false, 9, wrapped));
            }
        }
        return lines;
    }

    static List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        String rest = text.trim();
        while (rest.length() > width) {
            int cut = rest.lastIndexOf(' ', width);
            if (cut <= 0) {
                cut = width;
            }
            out.add(rest.substring(0, cut).trim());
            rest = rest.substring(cut).trim();
        }
        out.add(rest);
        return out;
    }

    /** The history as PDF bytes: a branded header and a footer on every page. */
    static byte[] buildPdf(List<HistoryRecord> records, Branding branding) {
        PdfDocument pdf = new PdfDocument();
        int logo = branding.addLogoTo(pdf);
        PdfDocument.Page page = pdf.addPage();
        float y = branding.drawCompactHeader(pdf, page, logo);
        for (PdfLine line : buildLines(records)) {
            if (y - line.height() < BOTTOM_Y) {
                page = pdf.addPage();
                y = branding.drawCompactHeader(pdf, page, logo);
            }
            if (!line.text.isEmpty()) {
                page.text(line.text, MARGIN_X, y, line.size, line.bold);
            }
            y -= line.height();
        }
        pdf.addFooters(branding.footerNote());
        return pdf.toBytes();
    }
}
