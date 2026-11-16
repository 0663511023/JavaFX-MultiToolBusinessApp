package application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The one shared connection to the MarginIQ database. It is opened the first time a screen needs it. On that first
 * open, everything the older versions kept in files is brought across, once, and the files are then deleted. A file
 * that cannot be fully brought across is kept under a new name, and the person is told why through Notices.
 * Pure logic, no JavaFX.
 */
public final class AppDatabase {

    private static MarginDatabase database;
    /** Old files that could not be read this time. They are never deleted, even if moving them aside failed. */
    private static final Set<Path> unreadable = new HashSet<>();

    private AppDatabase() {
    }

    /** The shared database, opened on first use. */
    public static synchronized MarginDatabase get() throws SQLException {
        if (database == null) {
            MarginDatabase db = MarginDatabase.open(MarginDatabase.file());
            try {
                bringAcross(db);
            } catch (SQLException | RuntimeException ex) {
                db.close();
                throw ex;
            }
            database = db;
        }
        return database;
    }

    /** Closes the shared database. The next use opens it again. Needed before the file is replaced by a restore. */
    public static synchronized void close() throws SQLException {
        if (database != null) {
            MarginDatabase db = database;
            database = null;
            db.close();
        }
    }

    /** The number the next quotation or invoice will get, such as QUO-0001. Looking at it does not use it up. */
    public static synchronized String peekNumber(QuoteDocument.Kind kind) throws SQLException {
        return MarginDatabase.formatNumber(kind, get().peekNumber(kind));
    }

    /**
     * Brings the old files across, once. Each file is deleted only after its contents are in the database. A file
     * that cannot be read, or cannot be fully brought across, is moved aside under another name and kept.
     */
    private static void bringAcross(MarginDatabase db) throws SQLException {
        unreadable.clear();

        // Clients. A clients file that cannot be read is moved aside, so it is not read as empty and then deleted.
        Path clientsFile = ClientStore.file();
        if (!readable(clientsFile)) {
            setAside(clientsFile, "unreadable", "The old clients file could not be read, so no clients were brought across.");
        }
        ClientStore.Legacy legacy = ClientStore.readLegacy(clientsFile);
        Set<String> seen = new HashSet<>();
        List<ClientProfile> clients = new ArrayList<>();
        List<String> duplicates = new ArrayList<>();
        for (ClientProfile c : legacy.clients()) {
            if (seen.add(c.name.toLowerCase(Locale.ROOT))) {
                clients.add(c);
            } else {
                duplicates.add(c.name);
            }
        }

        // The number counters were kept in the old branding file. If that file cannot be read, the counters are not
        // known, and may be behind the numbers already issued, so issuing waits until the person confirms them.
        Path brandingFile = LegacyFiles.brandingFile();
        boolean brandingReadable = readable(brandingFile);
        if (!brandingReadable) {
            db.markNumbersUnconfirmed();
            Notices.add("The quotation and invoice numbers could not be read from the old business details file. "
                    + "Quotations and invoices cannot be issued until you enter the next numbers in Settings.");
        }
        Map<String, String> branding = oldValues(brandingFile,
                "The old business details file could not be read, so the business details were not brought across.");
        if (db.importLegacy(clients, counter(branding.get("nextQuoteNumber")),
                counter(branding.get("nextInvoiceNumber")))) {
            if (legacy.skipped() > 0 || !duplicates.isEmpty()) {
                setAside(clientsFile, "not-all-imported", clientsMessage(legacy.skipped(), duplicates));
            } else {
                removeOld(clientsFile);
            }
        }

        // Settings, font, branding, logo and the history archive. The counters and the logo's path are not kept
        // as branding text: the counters are in the database already, and the logo is stored as its own image.
        Map<String, String> settings = oldValues(LegacyFiles.settingsFile(),
                "The old settings file could not be read, so its settings were not brought across.");
        Map<String, String> font = oldValues(LegacyFiles.fontFile(),
                "The old font file could not be read, so its font settings were not brought across.");
        Map<String, String> brandingValues = new LinkedHashMap<>(branding);
        brandingValues.remove("logoFile");
        brandingValues.remove("nextQuoteNumber");
        brandingValues.remove("nextInvoiceNumber");
        boolean logoReadable = true;
        MarginDatabase.StoredLogo logo = null;
        try {
            logo = LegacyFiles.logo(branding.get("logoFile"));
        } catch (IOException ex) {
            logoReadable = false; // its file is kept, and the home logo files are not deleted below
            Notices.add("The logo could not be read, so it was not brought across. Choose the logo again in Business Details.");
        }
        List<String> archive = oldLines(LegacyFiles.archiveFile(),
                "The old history archive could not be read, so it was not brought across.");
        if (db.importFilesOnce(settings, font, brandingValues, logo, archive)) {
            removeOld(LegacyFiles.settingsFile());
            removeOld(LegacyFiles.fontFile());
            removeOld(LegacyFiles.brandingFile());
            removeOld(LegacyFiles.archiveFile());
            if (logoReadable) {
                LegacyFiles.deleteHomeLogos();
            }
        }

        // Calculation history and pins
        Path historyFile = LegacyFiles.historyFile();
        LegacyFiles.History history;
        try {
            history = LegacyFiles.history(historyFile);
        } catch (IOException ex) {
            // Not readable text: it is kept aside under another name, so a later save cannot overwrite it
            setAside(historyFile, "unreadable", "The old calculation history could not be read, so it was not brought across.");
            history = new LegacyFiles.History(List.of(), 0);
        }
        Path pinsFile = LegacyFiles.pinsFile();
        if (db.importHistoryOnce(history.lines(), LegacyFiles.pins(pinsFile))) {
            if (history.skipped() > 0) {
                // Some lines were not in a form that could be brought across, so the file is kept for checking
                setAside(historyFile, "not-all-imported", history.skipped()
                        + (history.skipped() == 1
                        ? " line of the old calculation history could not be read, so it was not brought across."
                        : " lines of the old calculation history could not be read, so they were not brought across."));
            } else {
                removeOld(historyFile);
            }
            removeOld(pinsFile);
        }
    }

    /** True when the file is missing or can be read. */
    private static boolean readable(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return true;
        }
        try {
            LegacyFiles.properties(file);
            return true;
        } catch (IOException | RuntimeException ex) {
            return false;
        }
    }

    /** The values in an old properties file. A file that cannot be read is kept aside, reported, and gives no values. */
    private static Map<String, String> oldValues(Path file, String message) {
        try {
            return LegacyFiles.properties(file);
        } catch (IOException | RuntimeException ex) {
            setAside(file, "unreadable", message);
            return Map.of();
        }
    }

    /** The lines of an old text file. A file that cannot be read is kept aside, reported, and gives no lines. */
    private static List<String> oldLines(Path file, String message) {
        try {
            return LegacyFiles.lines(file);
        } catch (IOException | RuntimeException ex) {
            setAside(file, "unreadable", message);
            return List.of();
        }
    }

    private static String clientsMessage(int skipped, List<String> duplicates) {
        StringBuilder text = new StringBuilder("Not every client from the old clients file was brought across.");
        if (skipped > 0) {
            text.append(' ').append(skipped).append(skipped == 1 ? " entry could not be read." : " entries could not be read.");
        }
        if (!duplicates.isEmpty()) {
            text.append(" These clients have the same name as another client: ")
                    .append(String.join(", ", duplicates)).append('.');
        }
        return text.toString();
    }

    /** Deletes an old file once its contents are in the database. If it cannot be deleted it is left in place. */
    private static void removeOld(Path file) {
        if (file == null || unreadable.contains(file)) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ex) {
            // It stays where it is. It is not read again, because its contents were recorded already.
        }
    }

    /**
     * Moves a file that cannot be used aside under a new name, with the time, so it is kept but not read again, and
     * tells the person with the message and the new name. If the file cannot be renamed it is left where it is.
     */
    private static void setAside(Path file, String reason, String message) {
        if (file == null || !Files.exists(file)) {
            return;
        }
        unreadable.add(file);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path kept = file.resolveSibling(file.getFileName() + "." + reason + "-" + stamp);
        try {
            Files.move(file, kept);
            Notices.add(message + " It was kept in your home folder as " + kept.getFileName() + ".");
        } catch (IOException ex) {
            Notices.add(message + " The file could not be renamed, so it was left as " + file.getFileName() + ".");
        }
    }

    private static long counter(String raw) {
        try {
            return Math.max(1, Long.parseLong(raw == null ? "" : raw.trim()));
        } catch (NumberFormatException ex) {
            return 1;
        }
    }
}
