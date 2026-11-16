package application;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The SQLite database for clients and issued quotations and invoices. Stage 1: the schema, the client records,
 * the issued-document register (each document with a snapshot of what it showed the client), and the number
 * counters. Amounts are stored as exact decimal text, never as floating point. Plain JDBC, no ORM.
 * Pure logic with no JavaFX, so it can be tested on its own.
 */
public final class MarginDatabase implements AutoCloseable {

    /** The schema version this code expects. Change the tables only with a new step in migrate(). */
    public static final int SCHEMA_VERSION = 5;

    private static final String FILE_NAME = "marginiq.db";
    /** Set while the number counters could not be read from the old files. Its absence means they are trusted. */
    private static final String NUMBERS_UNCONFIRMED = "numbers_unconfirmed";

    private final Connection connection;
    /** How many transactions are open. Work inside an open transaction joins it rather than starting another. */
    private int depth = 0;

    private MarginDatabase(Connection connection) {
        this.connection = connection;
    }

    /** One saved client, with the id the database gives it. */
    public static final class StoredClient {
        public final long id;
        public final ClientProfile profile;

        StoredClient(long id, ClientProfile profile) {
            this.id = id;
            this.profile = profile;
        }
    }

    /** A line in the documents list for one client: what was issued, when, and for how much. */
    public static final class DocumentRow {
        public final String number;
        public final String kind;
        public final LocalDate date;
        public final String total;
        public final String pdfPath;
        public final String clientName;
        public final long id;

        DocumentRow(long id, String number, String kind, LocalDate date, String total, String pdfPath,
                    String clientName) {
            this.id = id;
            this.number = number;
            this.kind = kind;
            this.date = date;
            this.total = total;
            this.pdfPath = pdfPath;
            this.clientName = clientName;
        }
    }

    /** The logo image as it was saved in the database. */
    public static final class StoredLogo {
        public final String fileName;
        public final byte[] content;

        StoredLogo(String fileName, byte[] content) {
            this.fileName = fileName;
            this.content = content;
        }
    }

    /** The encrypted backup password as it is kept in the database. It cannot be opened without the key kept elsewhere. */
    public static final class SavedSecret {
        public final byte[] iv;
        public final byte[] ciphertext;

        SavedSecret(byte[] iv, byte[] ciphertext) {
            this.iv = iv;
            this.ciphertext = ciphertext;
        }
    }

    /** The database file in the user's home folder. */
    public static Path file() {
        return Paths.get(System.getProperty("user.home"), FILE_NAME);
    }

    /** Opens the database at this path, creating it if needed, and brings its schema up to date. */
    public static MarginDatabase open(Path file) throws SQLException {
        try {
            Path folder = file.toAbsolutePath().getParent();
            if (folder != null) {
                Files.createDirectories(folder);
            }
        } catch (IOException ex) {
            throw new SQLException("Could not create the folder for the database: " + ex.getMessage(), ex);
        }
        return connect("jdbc:sqlite:" + file.toAbsolutePath());
    }

    /** A database in memory, for tests. It is lost when closed. */
    static MarginDatabase openInMemory() throws SQLException {
        return connect("jdbc:sqlite::memory:");
    }

    private static MarginDatabase connect(String url) throws SQLException {
        Connection c = DriverManager.getConnection(url);
        MarginDatabase db = new MarginDatabase(c);
        try {
            try (Statement s = c.createStatement()) {
                s.execute("PRAGMA foreign_keys = ON");
            }
            db.migrate();
            return db;
        } catch (SQLException | RuntimeException ex) {
            c.close();
            throw ex;
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }

    /** The schema version stored in the file. */
    public synchronized int schemaVersion() throws SQLException {
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("PRAGMA user_version")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    // ------------------------------------------------------------------ schema

    private void migrate() throws SQLException {
        int version = schemaVersion();
        if (version > SCHEMA_VERSION) {
            throw new SQLException("This database was made by a newer version of MarginIQ Suite. "
                    + "Update the app before opening it.");
        }
        if (version < 1) {
            inTransaction(() -> {
                exec("CREATE TABLE clients ("
                        + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + " name TEXT NOT NULL UNIQUE COLLATE NOCASE,"
                        + " street TEXT NOT NULL DEFAULT '',"
                        + " suburb TEXT NOT NULL DEFAULT '',"
                        + " postal_code TEXT NOT NULL DEFAULT '',"
                        + " phone TEXT NOT NULL DEFAULT '',"
                        + " email TEXT NOT NULL DEFAULT '',"
                        + " vat_number TEXT NOT NULL DEFAULT '',"
                        + " charges_vat INTEGER NOT NULL DEFAULT 0,"
                        + " hourly_rate TEXT,"
                        + " markup_percent TEXT,"
                        + " sort_order INTEGER NOT NULL)");
                exec("CREATE TABLE documents ("
                        + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + " kind TEXT NOT NULL CHECK (kind IN ('QUOTE', 'INVOICE')),"
                        + " number TEXT NOT NULL UNIQUE,"
                        + " doc_date TEXT NOT NULL,"
                        + " valid_until TEXT,"
                        + " client_id INTEGER REFERENCES clients(id) ON DELETE SET NULL,"
                        + " client_name TEXT NOT NULL,"
                        + " client_street TEXT NOT NULL DEFAULT '',"
                        + " client_suburb TEXT NOT NULL DEFAULT '',"
                        + " client_postal_code TEXT NOT NULL DEFAULT '',"
                        + " client_phone TEXT NOT NULL DEFAULT '',"
                        + " client_email TEXT NOT NULL DEFAULT '',"
                        + " client_vat_number TEXT NOT NULL DEFAULT '',"
                        + " vat_rate TEXT NOT NULL,"
                        + " subtotal TEXT NOT NULL,"
                        + " vat TEXT NOT NULL,"
                        + " total TEXT NOT NULL,"
                        + " notes TEXT NOT NULL DEFAULT '',"
                        + " issued_at TEXT NOT NULL,"
                        + " pdf_path TEXT NOT NULL DEFAULT '')");
                exec("CREATE TABLE document_lines ("
                        + " document_id INTEGER NOT NULL REFERENCES documents(id) ON DELETE CASCADE,"
                        + " line_no INTEGER NOT NULL,"
                        + " description TEXT NOT NULL,"
                        + " quantity TEXT NOT NULL,"
                        + " unit_price TEXT NOT NULL,"
                        + " amount TEXT NOT NULL,"
                        + " PRIMARY KEY (document_id, line_no))");
                exec("CREATE TABLE number_counters ("
                        + " kind TEXT PRIMARY KEY CHECK (kind IN ('QUOTE', 'INVOICE')),"
                        + " next_number INTEGER NOT NULL)");
                exec("INSERT INTO number_counters (kind, next_number) VALUES ('QUOTE', 1), ('INVOICE', 1)");
                exec("PRAGMA user_version = 1");
                return null;
            });
        }
        if (version < 2) {
            inTransaction(() -> {
                exec("CREATE TABLE app_settings ("
                        + " setting_key TEXT PRIMARY KEY,"
                        + " setting_value TEXT NOT NULL)");
                exec("PRAGMA user_version = 2");
                return null;
            });
        }
        if (version < 3) {
            inTransaction(() -> {
                exec("CREATE TABLE history_entries ("
                        + " seq INTEGER PRIMARY KEY,"
                        + " line TEXT NOT NULL)");
                exec("CREATE TABLE pinned_calculations ("
                        + " seq INTEGER PRIMARY KEY,"
                        + " line TEXT NOT NULL)");
                exec("PRAGMA user_version = 3");
                return null;
            });
        }
        if (version < 4) {
            inTransaction(() -> {
                exec("CREATE TABLE history_archive ("
                        + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + " line TEXT NOT NULL,"
                        + " archived_at TEXT NOT NULL)");
                exec("CREATE TABLE logo ("
                        + " id INTEGER PRIMARY KEY CHECK (id = 1),"
                        + " file_name TEXT NOT NULL,"
                        + " content BLOB NOT NULL)");
                exec("PRAGMA user_version = 4");
                return null;
            });
        }
        if (version < 5) {
            inTransaction(() -> {
                exec("CREATE TABLE backup_password ("
                        + " id INTEGER PRIMARY KEY CHECK (id = 1),"
                        + " iv BLOB NOT NULL,"
                        + " ciphertext BLOB NOT NULL)");
                exec("PRAGMA user_version = 5");
                return null;
            });
        }
    }

    // ------------------------------------------------------------------ clients

    /** All saved clients, in the order they were added. */
    public synchronized List<StoredClient> clients() throws SQLException {
        List<StoredClient> list = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM clients ORDER BY sort_order");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(new StoredClient(rs.getLong("id"), profileFrom(rs)));
            }
        }
        return list;
    }

    /** Adds a client at the end of the list and returns its id. A duplicate name (any capitals) is refused. */
    public synchronized long addClient(ClientProfile profile) throws SQLException {
        return inTransaction(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO clients (name, street, suburb, postal_code, phone, email, vat_number,"
                            + " charges_vat, hourly_rate, markup_percent, sort_order)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                            + " (SELECT COALESCE(MAX(sort_order), 0) + 1 FROM clients))",
                    Statement.RETURN_GENERATED_KEYS)) {
                bindClient(ps, profile);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    return keys.getLong(1);
                }
            }
        });
    }

    /** Replaces the details of a saved client. Documents already issued keep the details they showed. */
    public synchronized void updateClient(long id, ClientProfile profile) throws SQLException {
        inTransaction(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE clients SET name = ?, street = ?, suburb = ?, postal_code = ?, phone = ?, email = ?,"
                            + " vat_number = ?, charges_vat = ?, hourly_rate = ?, markup_percent = ? WHERE id = ?")) {
                bindClient(ps, profile);
                ps.setLong(11, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Removes a saved client. Their issued documents stay, with the name and details they showed. */
    public synchronized void deleteClient(long id) throws SQLException {
        inTransaction(() -> {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM clients WHERE id = ?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    // ------------------------------------------------------------------ issued documents

    /**
     * Records a quotation or invoice that has been saved as a PDF: its lines, its totals and the client details
     * it showed. All of it is written in one transaction, so a failure leaves nothing half-recorded.
     * The client link may be null when the client has since been deleted or the document was typed in.
     */
    public synchronized long recordDocument(QuoteDocument doc, Long clientId, String pdfPath) throws SQLException {
        QuoteDocument.Totals totals = doc.totals();
        return inTransaction(() -> {
            long documentId;
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO documents (kind, number, doc_date, valid_until, client_id, client_name,"
                            + " client_street, client_suburb, client_postal_code, client_phone, client_email,"
                            + " client_vat_number, vat_rate, subtotal, vat, total, notes, issued_at, pdf_path)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, doc.kind.name());
                ps.setString(2, doc.number);
                ps.setString(3, doc.date.toString());
                ps.setString(4, doc.validUntil == null ? null : doc.validUntil.toString());
                if (clientId == null) {
                    ps.setNull(5, java.sql.Types.INTEGER);
                } else {
                    ps.setLong(5, clientId);
                }
                ps.setString(6, doc.clientName);
                ps.setString(7, doc.clientContact.street);
                ps.setString(8, doc.clientContact.suburb);
                ps.setString(9, doc.clientContact.postalCode);
                ps.setString(10, doc.clientContact.phone);
                ps.setString(11, doc.clientContact.email);
                ps.setString(12, doc.clientContact.vatNumber);
                ps.setString(13, doc.vatRate.toPlainString());
                ps.setString(14, totals.subtotal.toPlainString());
                ps.setString(15, totals.vat.toPlainString());
                ps.setString(16, totals.total.toPlainString());
                ps.setString(17, doc.notes);
                ps.setString(18, Instant.now().toString());
                ps.setString(19, pdfPath == null ? "" : pdfPath);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    documentId = keys.getLong(1);
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO document_lines (document_id, line_no, description, quantity, unit_price, amount)"
                            + " VALUES (?, ?, ?, ?, ?, ?)")) {
                for (int i = 0; i < doc.items.size(); i++) {
                    QuoteDocument.Item item = doc.items.get(i);
                    ps.setLong(1, documentId);
                    ps.setInt(2, i + 1);
                    ps.setString(3, item.description);
                    ps.setString(4, item.quantity.toPlainString());
                    ps.setString(5, item.unitPrice.toPlainString());
                    ps.setString(6, item.amount().toPlainString());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return documentId;
        });
    }

    /** The documents issued to one client, newest first. */
    public synchronized List<DocumentRow> documentsFor(long clientId) throws SQLException {
        return documentRows("SELECT id, number, kind, doc_date, total, pdf_path, client_name FROM documents"
                + " WHERE client_id = ? ORDER BY doc_date DESC, id DESC", clientId);
    }

    /**
     * The documents whose client has been deleted, or that were typed in without a saved client. They keep the name
     * they were issued under, so they are grouped by that name, then newest first.
     */
    public synchronized List<DocumentRow> documentsWithoutClient() throws SQLException {
        return documentRows("SELECT id, number, kind, doc_date, total, pdf_path, client_name FROM documents"
                + " WHERE client_id IS NULL ORDER BY client_name COLLATE NOCASE, doc_date DESC, id DESC", null);
    }

    private List<DocumentRow> documentRows(String sql, Long clientId) throws SQLException {
        List<DocumentRow> rows = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            if (clientId != null) {
                ps.setLong(1, clientId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new DocumentRow(rs.getLong("id"), rs.getString("number"), rs.getString("kind"),
                            LocalDate.parse(rs.getString("doc_date")), rs.getString("total"),
                            rs.getString("pdf_path"), rs.getString("client_name")));
                }
            }
        }
        return rows;
    }

    // ------------------------------------------------------------------ number counters

    /**
     * Takes the next quotation or invoice number in the sequence and moves the counter on. Returns the number
     * taken (1, 2, 3 ...). Quotations and invoices have separate counters.
     */
    public synchronized long takeNextNumber(QuoteDocument.Kind kind) throws SQLException {
        return inTransaction(() -> {
            long taken;
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT next_number FROM number_counters WHERE kind = ?")) {
                ps.setString(1, kind.name());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new SQLException("No number counter for " + kind + ".");
                    }
                    taken = rs.getLong(1);
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE number_counters SET next_number = ? WHERE kind = ?")) {
                ps.setLong(1, taken + 1);
                ps.setString(2, kind.name());
                ps.executeUpdate();
            }
            return taken;
        });
    }

    /** Sets the next number for a kind. Used when bringing in the numbers from the old settings file. */
    public synchronized void setNextNumber(QuoteDocument.Kind kind, long next) throws SQLException {
        if (next < 1) {
            throw new IllegalArgumentException("The next number must be 1 or more.");
        }
        inTransaction(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE number_counters SET next_number = ? WHERE kind = ?")) {
                ps.setLong(1, next);
                ps.setString(2, kind.name());
                ps.executeUpdate();
            }
            return null;
        });
    }

    /**
     * False while the counters could not be read from the old files, so they may be behind the numbers already
     * issued. Nothing is issued until the person confirms the next numbers in Settings.
     */
    public synchronized boolean numbersConfirmed() throws SQLException {
        return settingValue(NUMBERS_UNCONFIRMED) == null;
    }

    /** Marks the counters as untrusted until they are confirmed. */
    public synchronized void markNumbersUnconfirmed() throws SQLException {
        putSetting(NUMBERS_UNCONFIRMED, "yes");
    }

    /**
     * Confirms the next quotation and invoice numbers the person typed in. Each must be above the last number already
     * issued for its kind, so an issued number can never be used again. Clears the untrusted mark.
     */
    public synchronized void confirmNumbers(long nextQuote, long nextInvoice) throws SQLException {
        if (nextQuote < 1 || nextInvoice < 1) {
            throw new SQLException("The next number must be 1 or more.");
        }
        inTransaction(() -> {
            checkAbove(QuoteDocument.Kind.QUOTE, nextQuote);
            checkAbove(QuoteDocument.Kind.INVOICE, nextInvoice);
            setNextNumber(QuoteDocument.Kind.QUOTE, nextQuote);
            setNextNumber(QuoteDocument.Kind.INVOICE, nextInvoice);
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM app_settings WHERE setting_key = ?")) {
                ps.setString(1, NUMBERS_UNCONFIRMED);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Refuses a next number that is not above the last one issued for its kind. */
    private void checkAbove(QuoteDocument.Kind kind, long next) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COALESCE(MAX(CAST(substr(number, 5) AS INTEGER)), 0) FROM documents WHERE kind = ?")) {
            ps.setString(1, kind.name());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                long last = rs.getLong(1);
                if (next <= last) {
                    throw new SQLException("The next " + (kind == QuoteDocument.Kind.QUOTE ? "quotation" : "invoice")
                            + " number must be above " + formatNumber(kind, last)
                            + ", which has already been issued.");
                }
            }
        }
    }

    // ------------------------------------------------------------------ issuing a document with its number

    /** Writes a document's PDF. If it throws, the document is not recorded and its number is kept. */
    public interface PdfWriter {
        void write() throws IOException;
    }

    /**
     * Records a quotation or invoice and writes its PDF as one step. The number must be the next one in its sequence,
     * so a document that was opened before another one was saved cannot reuse a number. If the PDF cannot be written,
     * or the record cannot be saved, nothing is recorded and the number is kept.
     */
    public synchronized void issueDocument(QuoteDocument doc, Long clientId, Path pdf, PdfWriter writer)
            throws SQLException, IOException {
        if (!numbersConfirmed()) {
            throw new SQLException("Quotation and invoice numbers have not been confirmed, so nothing can be issued yet. "
                    + "Open Settings and enter the next quotation and invoice numbers.");
        }
        long expected;
        try {
            expected = Long.parseLong(doc.number.substring(doc.number.indexOf('-') + 1));
        } catch (RuntimeException ex) {
            throw new SQLException("The document number is not in the sequence: " + doc.number);
        }
        boolean auto = connection.getAutoCommit();
        connection.setAutoCommit(false);
        depth++;
        // Set once the PDF is being written, so a failure removes only a file this call has started to write
        boolean pdfStarted = false;
        try {
            if (peekNumber(doc.kind) != expected) {
                throw new SQLException("The next number has changed since this document was started. "
                        + "Close it and create it again.");
            }
            recordDocument(doc, clientId, pdf.toAbsolutePath().toString());
            pdfStarted = true;
            writer.write();
            takeNextNumber(doc.kind);
            try {
                connection.commit();
            } catch (SQLException ex) {
                // The PDF is written but cannot be recorded, so it is removed rather than left without a record
                Files.deleteIfExists(pdf);
                throw ex;
            }
        } catch (SQLException | IOException | RuntimeException ex) {
            connection.rollback();
            if (pdfStarted) {
                // A PDF that was only partly written is removed, so no file is left without its record
                try {
                    Files.deleteIfExists(pdf);
                } catch (IOException cleanup) {
                    ex.addSuppressed(cleanup);
                }
            }
            throw ex;
        } finally {
            depth--;
            connection.setAutoCommit(auto);
        }
    }

    /** The number the next quotation or invoice of this kind will get. Looking at it does not use it up. */
    public synchronized long peekNumber(QuoteDocument.Kind kind) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT next_number FROM number_counters WHERE kind = ?")) {
            ps.setString(1, kind.name());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("No number counter for " + kind + ".");
                }
                return rs.getLong(1);
            }
        }
    }

    /** A number as it is printed on a document, such as QUO-0001 or INV-0040. */
    public static String formatNumber(QuoteDocument.Kind kind, long value) {
        return (kind == QuoteDocument.Kind.QUOTE ? "QUO-" : "INV-")
                + String.format(java.util.Locale.ROOT, "%04d", value);
    }

    /**
     * Brings the clients and the next numbers over from the old settings files, once. A later call finds the mark
     * and does nothing, returning false. The clients and both numbers are written in one transaction.
     */
    public synchronized boolean importLegacy(List<ClientProfile> legacyClients, long nextQuote, long nextInvoice)
            throws SQLException {
        return inTransaction(() -> {
            if (settingValue("legacy_import") != null) {
                return false;
            }
            for (ClientProfile c : legacyClients) {
                addClient(c);
            }
            setNextNumber(QuoteDocument.Kind.QUOTE, nextQuote);
            setNextNumber(QuoteDocument.Kind.INVOICE, nextInvoice);
            putSetting("legacy_import", "done");
            return true;
        });
    }

    private String settingValue(String key) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT setting_value FROM app_settings WHERE setting_key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private void putSetting(String key, String value) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO app_settings (setting_key, setting_value) VALUES (?, ?)")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ calculation history and pins

    /** The calculation history lines, newest first. */
    public synchronized List<String> historyLines() throws SQLException {
        return linesFrom("history_entries");
    }

    /** Replaces the whole calculation history with these lines, newest first, in one transaction. */
    public synchronized void replaceHistory(List<String> lines) throws SQLException {
        replaceLines("history_entries", lines);
    }

    /** The pinned calculation lines, in the order they were pinned. */
    public synchronized List<String> pinnedLines() throws SQLException {
        return linesFrom("pinned_calculations");
    }

    /** Replaces the pinned calculations with these lines, in order, in one transaction. */
    public synchronized void replacePins(List<String> lines) throws SQLException {
        replaceLines("pinned_calculations", lines);
    }

    /**
     * Brings the old calculation history and pins across, once. A later call finds the mark and does nothing,
     * returning false.
     */
    public synchronized boolean importHistoryOnce(List<String> history, List<String> pins) throws SQLException {
        return inTransaction(() -> {
            if (settingValue("history_import") != null) {
                return false;
            }
            replaceLines("history_entries", history);
            replaceLines("pinned_calculations", pins);
            putSetting("history_import", "done");
            return true;
        });
    }

    private List<String> linesFrom(String table) throws SQLException {
        List<String> list = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT line FROM " + table + " ORDER BY seq");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(rs.getString(1));
            }
        }
        return list;
    }

    private void replaceLines(String table, List<String> lines) throws SQLException {
        inTransaction(() -> {
            exec("DELETE FROM " + table);
            if (!lines.isEmpty()) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO " + table + " (seq, line) VALUES (?, ?)")) {
                    for (int i = 0; i < lines.size(); i++) {
                        ps.setInt(1, i + 1);
                        ps.setString(2, lines.get(i));
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }
            return null;
        });
    }

    // ------------------------------------------------------------------ settings, logo, archive, document delete

    /** The saved values in one settings group ("settings", "font" or "branding"), keyed without the group name. */
    public synchronized Map<String, String> settingGroup(String group) throws SQLException {
        Map<String, String> values = new LinkedHashMap<>();
        String prefix = group + ".";
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT setting_key, setting_value FROM app_settings WHERE substr(setting_key, 1, ?) = ?")) {
            ps.setInt(1, prefix.length());
            ps.setString(2, prefix);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    values.put(rs.getString(1).substring(prefix.length()), rs.getString(2));
                }
            }
        }
        return values;
    }

    /** Replaces every value in one settings group, in one transaction. */
    public synchronized void replaceSettingGroup(String group, Map<String, String> values) throws SQLException {
        String prefix = group + ".";
        inTransaction(() -> {
            try (PreparedStatement del = connection.prepareStatement(
                    "DELETE FROM app_settings WHERE substr(setting_key, 1, ?) = ?")) {
                del.setInt(1, prefix.length());
                del.setString(2, prefix);
                del.executeUpdate();
            }
            if (!values.isEmpty()) {
                try (PreparedStatement ins = connection.prepareStatement(
                        "INSERT INTO app_settings (setting_key, setting_value) VALUES (?, ?)")) {
                    for (Map.Entry<String, String> e : values.entrySet()) {
                        ins.setString(1, prefix + e.getKey());
                        ins.setString(2, e.getValue());
                        ins.addBatch();
                    }
                    ins.executeBatch();
                }
            }
            return null;
        });
    }

    /** Adds history lines to the archive: the older entries that the retention setting has taken off the list. */
    public synchronized void archiveHistory(List<String> lines) throws SQLException {
        if (lines.isEmpty()) {
            return;
        }
        String when = LocalDateTime.now().toString();
        inTransaction(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO history_archive (line, archived_at) VALUES (?, ?)")) {
                for (String line : lines) {
                    ps.setString(1, line);
                    ps.setString(2, when);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    /**
     * Every archived history line, in the order they were archived. No screen shows the archive yet; this reads it for
     * the import check, which confirms the archive came across in order.
     */
    synchronized List<String> archivedLines() throws SQLException {
        List<String> list = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT line FROM history_archive ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(rs.getString(1));
            }
        }
        return list;
    }

    /** Saves the logo for quotes, invoices and reports. There is one logo, so a new one replaces the old. */
    public synchronized void saveLogo(String fileName, byte[] content) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO logo (id, file_name, content) VALUES (1, ?, ?)")) {
            ps.setString(1, fileName);
            ps.setBytes(2, content);
            ps.executeUpdate();
        }
    }

    /** The saved logo, or null when there is none. */
    public synchronized StoredLogo logo() throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT file_name, content FROM logo WHERE id = 1");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? new StoredLogo(rs.getString(1), rs.getBytes(2)) : null;
        }
    }

    /** Saves the encrypted backup password. There is one, so a new one replaces the old. */
    public synchronized void saveBackupPassword(byte[] iv, byte[] ciphertext) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO backup_password (id, iv, ciphertext) VALUES (1, ?, ?)")) {
            ps.setBytes(1, iv);
            ps.setBytes(2, ciphertext);
            ps.executeUpdate();
        }
    }

    /** The saved backup password in encrypted form, or null when none has been saved. */
    public synchronized SavedSecret backupPassword() throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT iv, ciphertext FROM backup_password WHERE id = 1");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? new SavedSecret(rs.getBytes(1), rs.getBytes(2)) : null;
        }
    }

    /** Removes an issued quotation or invoice and its lines. Its number is not used again. */
    public synchronized void deleteDocument(long id) throws SQLException {
        inTransaction(() -> {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM documents WHERE id = ?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /**
     * Brings the old settings, font, branding, logo and history archive across, once. A later call finds the
     * mark and does nothing, returning false. A group is only written when its old file had something in it.
     */
    public synchronized boolean importFilesOnce(Map<String, String> settings, Map<String, String> font,
                                                Map<String, String> branding, StoredLogo logo,
                                                List<String> archive) throws SQLException {
        return inTransaction(() -> {
            if (settingValue("files_import") != null) {
                return false;
            }
            if (!settings.isEmpty()) {
                replaceSettingGroup("settings", settings);
            }
            if (!font.isEmpty()) {
                replaceSettingGroup("font", font);
            }
            if (!branding.isEmpty()) {
                replaceSettingGroup("branding", branding);
            }
            if (logo != null) {
                saveLogo(logo.fileName, logo.content);
            }
            archiveHistory(archive);
            putSetting("files_import", "done");
            return true;
        });
    }

    // ------------------------------------------------------------------ backup

    /**
     * Writes a complete, consistent copy of the database to the target file. It is made by SQLite itself, so it is
     * safe even while the app is open. The target is replaced if it already exists.
     */
    public synchronized void backupTo(Path target) throws SQLException, IOException {
        Path absolute = target.toAbsolutePath();
        Files.deleteIfExists(absolute);
        try (Statement s = connection.createStatement()) {
            s.execute("VACUUM INTO '" + absolute.toString().replace("'", "''") + "'");
        }
    }

    // ------------------------------------------------------------------ helpers

    /** A piece of work that must succeed or fail as a whole. */
    private interface Work<T> {
        T run() throws SQLException;
    }

    private <T> T inTransaction(Work<T> work) throws SQLException {
        if (depth > 0) {
            // Already inside a transaction that the caller opened: join it, and let the caller commit
            return work.run();
        }
        boolean auto = connection.getAutoCommit();
        connection.setAutoCommit(false);
        depth++;
        try {
            T result = work.run();
            connection.commit();
            return result;
        } catch (SQLException | RuntimeException ex) {
            connection.rollback();
            throw ex;
        } finally {
            depth--;
            connection.setAutoCommit(auto);
        }
    }

    private void exec(String sql) throws SQLException {
        try (Statement s = connection.createStatement()) {
            s.execute(sql);
        }
    }

    private static void bindClient(PreparedStatement ps, ClientProfile p) throws SQLException {
        ps.setString(1, p.name);
        ps.setString(2, p.contact.street);
        ps.setString(3, p.contact.suburb);
        ps.setString(4, p.contact.postalCode);
        ps.setString(5, p.contact.phone);
        ps.setString(6, p.contact.email);
        ps.setString(7, p.contact.vatNumber);
        ps.setInt(8, p.chargesVat ? 1 : 0);
        ps.setString(9, text(p.hourlyRate));
        ps.setString(10, text(p.markupPercent));
    }

    private static ClientProfile profileFrom(ResultSet rs) throws SQLException {
        ContactDetails contact = new ContactDetails(
                rs.getString("street"), rs.getString("suburb"), rs.getString("postal_code"),
                rs.getString("phone"), rs.getString("email"), rs.getString("vat_number"));
        return new ClientProfile(rs.getString("name"), contact, rs.getInt("charges_vat") == 1,
                decimal(rs.getString("hourly_rate")), decimal(rs.getString("markup_percent")));
    }

    private static String text(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private static BigDecimal decimal(String raw) {
        return raw == null ? null : new BigDecimal(raw);
    }
}
