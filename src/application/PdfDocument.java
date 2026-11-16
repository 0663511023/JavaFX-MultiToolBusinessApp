package application;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Deflater;

/**
 * A small PDF writer for A4 pages: text in Helvetica, filled rectangles, lines, images and page footers.
 * Written with the Java standard library only, so no extra jars are needed. Pure logic with no JavaFX.
 *
 * Coordinates are in points, with (0, 0) at the bottom left of the page, as PDF uses.
 */
public final class PdfDocument {

    /** A4 page size in points. */
    public static final float WIDTH = 595f;
    public static final float HEIGHT = 842f;

    /** Colours as red, green and blue from 0 to 1. */
    public static final float[] BLACK = {0f, 0f, 0f};
    public static final float[] GREY = {0.42f, 0.42f, 0.42f};
    public static final float[] RULE = {0.82f, 0.82f, 0.82f};
    public static final float[] TINT = {0.91f, 0.98f, 0.95f};
    public static final float[] EMERALD = {0.063f, 0.506f, 0.353f};
    /** The colour of a clickable email address: a clear link blue. */
    public static final float[] LINK = {0.05f, 0.33f, 0.75f};

    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9._%+\\-]+@[A-Za-z0-9\\-]+(\\.[A-Za-z0-9\\-]+)*\\.[A-Za-z]{2,}");
    private static final Charset WIN_1252 = Charset.forName("windows-1252");
    private static final int MAX_IMAGE_SIDE = 600;

    /** Helvetica and Helvetica-Bold widths for the characters 32 to 126, in thousandths of a point. */
    private static final int[] REGULAR_WIDTHS = {
        278, 278, 355, 556, 556, 889, 667, 191, 333, 333,
        389, 584, 278, 333, 278, 278, 556, 556, 556, 556,
        556, 556, 556, 556, 556, 556, 278, 278, 584, 584,
        584, 556, 1015, 667, 667, 722, 722, 667, 611, 778,
        722, 278, 500, 667, 556, 833, 722, 778, 667, 778,
        722, 667, 611, 722, 667, 944, 667, 667, 611, 278,
        278, 278, 469, 556, 333, 556, 556, 500, 556, 556,
        278, 556, 556, 222, 222, 500, 222, 833, 556, 556,
        556, 556, 333, 500, 278, 556, 500, 722, 500, 500,
        500, 334, 260, 334, 584
    };
    private static final int[] BOLD_WIDTHS = {
        278, 333, 474, 556, 556, 889, 722, 238, 333, 333,
        389, 584, 278, 333, 278, 278, 556, 556, 556, 556,
        556, 556, 556, 556, 556, 556, 333, 333, 584, 584,
        584, 611, 975, 722, 722, 722, 722, 667, 611, 778,
        722, 278, 556, 722, 611, 833, 722, 778, 667, 778,
        722, 667, 611, 722, 667, 944, 667, 667, 611, 333,
        278, 333, 584, 556, 333, 556, 611, 556, 611, 556,
        333, 611, 611, 278, 278, 556, 278, 889, 611, 611,
        611, 611, 389, 556, 333, 611, 556, 778, 556, 556,
        500, 389, 280, 389, 584
    };

    private final List<Page> pages = new ArrayList<>();
    /** Each image as a complete PDF object body: its dictionary and its compressed pixels. */
    private final List<byte[]> imageObjects = new ArrayList<>();
    private final List<int[]> imageSizes = new ArrayList<>();

    public PdfDocument() {}

    /** Adds a blank A4 page and returns it, ready to draw on. */
    public Page addPage() {
        Page page = new Page();
        pages.add(page);
        return page;
    }

    public List<Page> pages() {
        return Collections.unmodifiableList(pages);
    }

    /** The width of an image in pixels, as stored. */
    public int imageWidth(int index) {
        return imageSizes.get(index)[0];
    }

    /** The height of an image in pixels, as stored. */
    public int imageHeight(int index) {
        return imageSizes.get(index)[1];
    }

    /**
     * Stores an image in the document and returns its index, for Page.image. Transparent areas are
     * printed on white. Large images are scaled down, which keeps the file small.
     */
    public int addImage(BufferedImage source) {
        double scale = Math.min(1.0, (double) MAX_IMAGE_SIDE / Math.max(source.getWidth(), source.getHeight()));
        int w = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(source.getHeight() * scale));

        BufferedImage rgb = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(source, 0, 0, w, h, null);
        g.dispose();

        byte[] raw = new byte[w * h * 3];
        int k = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = rgb.getRGB(x, y);
                raw[k++] = (byte) (argb >> 16);
                raw[k++] = (byte) (argb >> 8);
                raw[k++] = (byte) argb;
            }
        }
        byte[] packed = deflate(raw);

        ByteArrayOutputStream object = new ByteArrayOutputStream();
        write(object, "<< /Type /XObject /Subtype /Image /Width " + w + " /Height " + h
                + " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode /Length " + packed.length
                + " >>\nstream\n");
        object.writeBytes(packed);
        write(object, "\nendstream");

        imageObjects.add(object.toByteArray());
        imageSizes.add(new int[] {w, h});
        return imageObjects.size() - 1;
    }

    /** Writes a footer on every page: an optional note on the left, and "Page n of N" on the right. */
    public void addFooters(String note) {
        int total = pages.size();
        for (int i = 0; i < total; i++) {
            Page page = pages.get(i);
            page.line(40, 56, WIDTH - 40, 56, 0.5f, RULE);
            if (note != null && !note.isBlank()) {
                page.text(note, 40, 40, 8, false, GREY);
            }
            page.textRight("Page " + (i + 1) + " of " + total, WIDTH - 40, 40, 8, false, GREY);
        }
    }

    /** The width of a line of text in points, using the Helvetica widths. */
    public static float textWidth(String text, float size, boolean bold) {
        int[] table = bold ? BOLD_WIDTHS : REGULAR_WIDTHS;
        int total = 0;
        for (char c : text.toCharArray()) {
            if (c >= 32 && c <= 126) {
                total += table[c - 32];
            } else if (c == ' ') {
                total += 278; // a non-breaking space is as wide as a space
            } else {
                total += 556; // a rough width for any other character
            }
        }
        return total * size / 1000f;
    }

    /**
     * Splits text into lines no wider than maxWidth. Each line break in the text starts a new line, and
     * blank lines are dropped. A single word wider than the line is kept whole.
     */
    public static List<String> wrap(String text, float maxWidth, float size, boolean bold) {
        List<String> lines = new ArrayList<>();
        if (text == null) {
            return lines;
        }
        for (String paragraph : text.split("\n")) {
            String current = "";
            for (String word : paragraph.trim().split("\\s+")) {
                if (word.isEmpty()) {
                    continue;
                }
                String candidate = current.isEmpty() ? word : current + " " + word;
                if (current.isEmpty() || textWidth(candidate, size, bold) <= maxWidth) {
                    current = candidate;
                } else {
                    lines.add(current);
                    current = word;
                }
            }
            if (!current.isEmpty()) {
                lines.add(current);
            }
        }
        return lines;
    }

    /** The size of an image scaled down to fit inside maxWidth by maxHeight, never enlarged. */
    public static float[] fit(float width, float height, float maxWidth, float maxHeight) {
        double scale = Math.min(1.0, Math.min(maxWidth / width, maxHeight / height));
        return new float[] {(float) (width * scale), (float) (height * scale)};
    }

    /** Writes the whole document as PDF bytes. */
    public byte[] toBytes() {
        if (pages.isEmpty()) {
            addPage();
        }
        int n = pages.size();
        int imageCount = imageObjects.size();

        // Object numbers: 1 catalog, 2 page tree, 3 Helvetica, 4 Helvetica-Bold, then the images,
        // then for each page its page object followed by its content stream.
        int firstPageObject = 5 + imageCount;
        List<byte[]> objects = new ArrayList<>();
        objects.add(bytes("<< /Type /Catalog /Pages 2 0 R >>"));

        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < n; i++) {
            kids.append(firstPageObject + 2 * i).append(" 0 R ");
        }
        objects.add(bytes("<< /Type /Pages /Kids [" + kids.toString().trim() + "] /Count " + n + " >>"));
        objects.add(bytes("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"));
        objects.add(bytes("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>"));
        objects.addAll(imageObjects);

        StringBuilder xobjects = new StringBuilder();
        if (imageCount > 0) {
            xobjects.append(" /XObject << ");
            for (int i = 0; i < imageCount; i++) {
                xobjects.append("/Im").append(i).append(' ').append(5 + i).append(" 0 R ");
            }
            xobjects.append(">>");
        }
        // Link annotations follow the last page and its content, so their object numbers come after those
        int nextAnnotation = firstPageObject + 2 * n;
        List<byte[]> annotations = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            StringBuilder annots = new StringBuilder();
            for (Page.Link link : pages.get(i).links()) {
                annots.append(nextAnnotation++).append(" 0 R ");
                annotations.add(bytes(linkAnnotation(link)));
            }
            String annotsEntry = annots.length() == 0 ? "" : " /Annots [" + annots.toString().trim() + "]";
            objects.add(bytes("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842]"
                    + " /Resources << /Font << /F1 3 0 R /F2 4 0 R >>" + xobjects + " >>"
                    + annotsEntry
                    + " /Contents " + (firstPageObject + 2 * i + 1) + " 0 R >>"));
            byte[] content = bytes(pages.get(i).content());
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            write(stream, "<< /Length " + content.length + " >>\nstream\n");
            stream.writeBytes(content);
            write(stream, "endstream");
            objects.add(stream.toByteArray());
        }
        objects.addAll(annotations);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, "%PDF-1.4\n%âãÏÓ\n");
        List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(out.size());
            write(out, (i + 1) + " 0 obj\n");
            out.writeBytes(objects.get(i));
            write(out, "\nendobj\n");
        }

        int xrefPosition = out.size();
        StringBuilder xref = new StringBuilder();
        xref.append("xref\n0 ").append(objects.size() + 1).append('\n');
        xref.append("0000000000 65535 f \n");
        for (int offset : offsets) {
            xref.append(String.format(Locale.ROOT, "%010d 00000 n \n", offset));
        }
        xref.append("trailer\n<< /Size ").append(objects.size() + 1).append(" /Root 1 0 R >>\n");
        xref.append("startxref\n").append(xrefPosition).append("\n%%EOF\n");
        write(out, xref.toString());
        return out.toByteArray();
    }

    /** One page of drawing commands. */
    public static final class Page {
        private final StringBuilder ops = new StringBuilder();
        private final List<Link> links = new ArrayList<>();

        private Page() {}

        /** Text at (x, y), where y is the baseline. */
        public void text(String text, float x, float y, float size, boolean bold) {
            text(text, x, y, size, bold, BLACK);
        }

        public void text(String text, float x, float y, float size, boolean bold, float[] rgb) {
            ops.append(colour("rg", rgb))
                    .append("BT /").append(bold ? "F2" : "F1").append(' ').append(num(size)).append(" Tf ")
                    .append(num(x)).append(' ').append(num(y)).append(" Td (")
                    .append(encode(text)).append(") Tj ET\n");
        }

        /** Text whose right edge sits at xRight. */
        public void textRight(String text, float xRight, float y, float size, boolean bold, float[] rgb) {
            text(text, xRight - textWidth(text, size, bold), y, size, bold, rgb);
        }

        public void fillRect(float x, float y, float width, float height, float[] rgb) {
            ops.append(colour("rg", rgb))
                    .append(num(x)).append(' ').append(num(y)).append(' ')
                    .append(num(width)).append(' ').append(num(height)).append(" re f\n");
        }

        public void line(float x1, float y1, float x2, float y2, float width, float[] rgb) {
            ops.append(colour("RG", rgb)).append(num(width)).append(" w ")
                    .append(num(x1)).append(' ').append(num(y1)).append(" m ")
                    .append(num(x2)).append(' ').append(num(y2)).append(" l S\n");
        }

        /** An image from PdfDocument.addImage, drawn at (x, y) with the given width and height. */
        public void image(int index, float x, float y, float width, float height) {
            ops.append("q ").append(num(width)).append(" 0 0 ").append(num(height)).append(' ')
                    .append(num(x)).append(' ').append(num(y)).append(" cm /Im")
                    .append(index).append(" Do Q\n");
        }

        /** A clickable area over the page that opens the target, such as mailto:name@example.com. */
        public void link(float x, float y, float width, float height, String target) {
            links.add(new Link(x, y, width, height, target));
        }

        /**
         * Text in which every email address is drawn in the link colour, underlined, and made a clickable
         * mailto link. Everything else is drawn in rgb, as with text().
         */
        public void textWithLinks(String text, float x, float y, float size, boolean bold, float[] rgb) {
            Matcher m = EMAIL.matcher(text);
            float cursor = x;
            int last = 0;
            while (m.find()) {
                String before = text.substring(last, m.start());
                if (!before.isEmpty()) {
                    text(before, cursor, y, size, bold, rgb);
                    cursor += textWidth(before, size, bold);
                }
                String email = m.group();
                float width = textWidth(email, size, bold);
                text(email, cursor, y, size, bold, LINK);
                float underline = y - size * 0.18f;
                line(cursor, underline, cursor + width, underline, 0.6f, LINK);
                link(cursor, y - size * 0.25f, width, size * 1.1f, "mailto:" + email);
                cursor += width;
                last = m.end();
            }
            String rest = text.substring(last);
            if (!rest.isEmpty()) {
                text(rest, cursor, y, size, bold, rgb);
            }
        }

        List<Link> links() {
            return links;
        }

        String content() {
            return ops.toString();
        }

        /** A clickable rectangle in page coordinates and where it leads. */
        static final class Link {
            final float x;
            final float y;
            final float w;
            final float h;
            final String target;

            Link(float x, float y, float w, float h, String target) {
                this.x = x;
                this.y = y;
                this.w = w;
                this.h = h;
                this.target = target;
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    /** The annotation dictionary for one clickable link. The link is drawn with no border. */
    private static String linkAnnotation(Page.Link link) {
        return "<< /Type /Annot /Subtype /Link /Rect [" + num(link.x) + " " + num(link.y) + " "
                + num(link.x + link.w) + " " + num(link.y + link.h) + "] /Border [0 0 0]"
                + " /A << /S /URI /URI (" + encode(link.target) + ") >> >>";
    }

    private static String colour(String operator, float[] rgb) {
        return num(rgb[0]) + " " + num(rgb[1]) + " " + num(rgb[2]) + " " + operator + "\n";
    }

    private static String num(float value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /**
     * Text as WinAnsi bytes, carried in a string of one character per byte. Characters that WinAnsi
     * cannot show become '?', and the PDF special characters are escaped.
     */
    static String encode(String text) {
        byte[] raw = text.getBytes(WIN_1252);
        StringBuilder sb = new StringBuilder(raw.length + 8);
        for (byte b : raw) {
            int c = b & 0xFF;
            if (c == '\\' || c == '(' || c == ')') {
                sb.append('\\');
            }
            sb.append((char) c);
        }
        return sb.toString();
    }

    private static byte[] deflate(byte[] raw) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        deflater.setInput(raw);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 4 + 64);
        byte[] buffer = new byte[8192];
        while (!deflater.finished()) {
            int n = deflater.deflate(buffer);
            out.write(buffer, 0, n);
        }
        deflater.end();
        return out.toByteArray();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static void write(ByteArrayOutputStream out, String text) {
        out.writeBytes(bytes(text));
    }
}
