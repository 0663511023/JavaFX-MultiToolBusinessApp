package application;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.imageio.ImageIO;

/**
 * The business details printed on exported reports, quotes and invoices: name, contact details, VAT number,
 * logo and footer note. They are kept in the MarginIQ database, and the logo is stored there as an image too.
 * Older branding saved with one free-text address is still read. Pure logic with no JavaFX.
 */
public final class Branding {

    public static final String DEFAULT_FOOTER = "Generated via MarginIQ Suite";
    public static final int MAX_NAME_LENGTH = 80;
    public static final int MAX_FOOTER_LENGTH = 150;
    /** The largest logo that can be saved. A bigger picture is refused, so the database stays small. */
    public static final long MAX_LOGO_BYTES = 5L * 1024 * 1024;

    private static final String[] LOGO_TYPES = {"png", "jpg", "jpeg"};

    private static volatile Branding current = read();

    /** True once the saved branding has been read from the database. Until then nothing is written. */
    private static volatile boolean readFromDatabase;

    private final String businessName;
    private final ContactDetails contact;
    /** The name of the saved logo, such as marginiq_logo.png, or an empty string when there is no logo. */
    private final String logoFile;
    private final String footerNote;

    private Branding(String businessName, ContactDetails contact, String logoFile, String footerNote) {
        this.businessName = businessName;
        this.contact = contact;
        this.logoFile = logoFile;
        this.footerNote = footerNote;
    }

    /** The branding saved in the database, as it was last read or saved. */
    public static Branding current() {
        return current;
    }

    /** Reads the branding from the database again. Used by the Refresh button and after a restore. */
    public static void reload() {
        current = read();
    }

    /** Reads the saved branding and logo. If the database cannot be read, the empty defaults are used. */
    private static Branding read() {
        Map<String, String> p;
        MarginDatabase.StoredLogo logo;
        try {
            MarginDatabase db = AppDatabase.get();
            p = db.settingGroup("branding");
            logo = db.logo();
            readFromDatabase = true;
            Notices.clearOnce("branding-read");
        } catch (SQLException | RuntimeException ex) {
            Notices.addOnce("branding-read", "The business details could not be read from the database, so they are shown "
                    + "empty, and changes will not be saved until you press Refresh (F5), or restart MarginIQ Suite. ("
                    + ex.getMessage() + ")");
            p = Map.of();
            logo = null;
            readFromDatabase = false;
        }
        // Branding saved before the contact fields were split kept the whole address in one box
        String street = p.get("street") != null ? p.get("street") : p.get("businessDetails");
        ContactDetails contact = new ContactDetails(
                text(street),
                text(p.get("suburb")),
                text(p.get("postal")),
                text(p.get("phone")),
                text(p.get("email")),
                text(p.get("vatNumber")));
        return new Branding(
                text(p.get("businessName")),
                contact,
                logo == null ? "" : logo.fileName,
                p.get("footerNote") == null ? DEFAULT_FOOTER : text(p.get("footerNote")));
    }

    private static String text(String raw) {
        return raw == null ? "" : raw.trim();
    }

    /** Saves this branding to the database, then makes it the current branding. Nothing changes if the save fails. */
    public void save() throws IOException {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("businessName", businessName);
        p.put("street", contact.street);
        p.put("suburb", contact.suburb);
        p.put("postal", contact.postalCode);
        p.put("phone", contact.phone);
        p.put("email", contact.email);
        p.put("vatNumber", contact.vatNumber);
        p.put("footerNote", footerNote);
        if (!readFromDatabase) {
            throw new IOException("The business details could not be read, so nothing has been changed. "
                    + "Press Refresh (F5), or restart MarginIQ Suite.");
        }
        try {
            AppDatabase.get().replaceSettingGroup("branding", p);
        } catch (SQLException ex) {
            throw new IOException("Could not save the business details: " + ex.getMessage(), ex);
        }
        current = this;
    }

    /** A copy with new business details. The logo is the saved logo's name, or an empty string for none. */
    public Branding withDetails(String name, ContactDetails details, String logo, String footer) {
        return new Branding(name, details, logo, footer);
    }

    public String businessName() {
        return businessName;
    }

    public ContactDetails contact() {
        return contact;
    }

    /** The name of the saved logo, or an empty string when there is none. The image itself is in the database. */
    public String logoFile() {
        return logoFile;
    }

    public String footerNote() {
        return footerNote;
    }

    /** True when a VAT number is given, so invoices can be tax invoices. */
    public boolean hasVatNumber() {
        return contact.hasVatNumber();
    }

    /**
     * A compact header for the top of a page: the logo at the top right and the business name at the top
     * left, with a rule under both. Returns the height where the page content can start.
     */
    public float drawCompactHeader(PdfDocument pdf, PdfDocument.Page page, int logo) {
        float top = 800;
        float logoBottom = top;
        if (logo >= 0) {
            float[] size = PdfDocument.fit(pdf.imageWidth(logo), pdf.imageHeight(logo), 110, 40);
            page.image(logo, 555 - size[0], top - size[1], size[0], size[1]);
            logoBottom = top - size[1];
        }
        float y = top - 14;
        if (!businessName.isEmpty()) {
            for (String line : PdfDocument.wrap(businessName, 380, 12, true)) {
                page.text(line, 40, y, 12, true);
                y -= 15;
            }
        }
        float rule = Math.min(logoBottom, y + 15) - 10;
        page.line(40, rule, 555, rule, 0.8f, PdfDocument.RULE);
        return rule - 22;
    }

    /**
     * Adds the logo to a PDF and returns its image index, or -1 when there is no usable logo.
     * A missing or unreadable logo is not an error: the document is simply printed without it.
     */
    public int addLogoTo(PdfDocument pdf) {
        if (logoFile.isEmpty()) {
            return -1;
        }
        try {
            MarginDatabase.StoredLogo stored = AppDatabase.get().logo();
            if (stored == null) {
                return -1;
            }
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(stored.content));
            return image == null ? -1 : pdf.addImage(image);
        } catch (SQLException | IOException | RuntimeException ex) {
            return -1;
        }
    }

    // ---------------------------------------------------------------- checks

    /** The business name: optional, at most 80 characters. */
    public static String checkName(String raw) throws InputParser.InvalidInputException {
        String value = text(raw);
        if (value.length() > MAX_NAME_LENGTH) {
            throw new InputParser.InvalidInputException("The business name can be at most "
                    + MAX_NAME_LENGTH + " characters.");
        }
        return value;
    }

    /** The footer note: optional, at most 150 characters. A blank note prints no footer text. */
    public static String checkFooter(String raw) throws InputParser.InvalidInputException {
        String value = text(raw);
        if (value.length() > MAX_FOOTER_LENGTH) {
            throw new InputParser.InvalidInputException("The footer note can be at most "
                    + MAX_FOOTER_LENGTH + " characters.");
        }
        return value;
    }

    /** True when the file name ends in one of the image types the logo can use. */
    public static boolean isLogoType(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String type : LOGO_TYPES) {
            if (lower.endsWith("." + type)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Saves a logo picture in the database, so it still prints if the original is moved or deleted.
     * Returns the saved logo's name. Only PNG and JPEG files of up to MAX_LOGO_BYTES are accepted.
     */
    public static String importLogo(String sourcePath) throws IOException, InputParser.InvalidInputException {
        Path source;
        try {
            source = Paths.get(sourcePath.trim());
        } catch (InvalidPathException ex) {
            throw new InputParser.InvalidInputException("That logo file path is not valid.");
        }
        if (!Files.isRegularFile(source) || !isLogoType(source.getFileName().toString())) {
            throw new InputParser.InvalidInputException("Choose a PNG or JPEG logo file that exists.");
        }
        if (Files.size(source) > MAX_LOGO_BYTES) {
            throw new InputParser.InvalidInputException("The logo file is too large. Choose one under 5 MB.");
        }
        String name = source.getFileName().toString();
        byte[] content = Files.readAllBytes(source);
        try {
            AppDatabase.get().saveLogo(name, content);
        } catch (SQLException ex) {
            throw new IOException("Could not save the logo: " + ex.getMessage(), ex);
        }
        return name;
    }
}
