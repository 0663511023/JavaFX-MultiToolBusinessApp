package application;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The saved clients. They are kept in the MarginIQ database (see AppDatabase). This class gives the screens the list,
 * applies each change, and tells the listeners when the list changes. The old clients file is only read, once, to
 * bring the clients across. There is no limit on the number of clients. Pure logic with no JavaFX.
 */
public final class ClientStore {

    private static final String LEGACY_FILE_NAME = "marginiq_clients.properties";

    /**
     * The clients read from an old clients file. Skipped counts the entries that could not be read, so the caller
     * can keep the file rather than delete the entries that were lost.
     */
    public record Legacy(List<ClientProfile> clients, int skipped) {
    }

    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    /** The saved clients with their database ids. Null until they have been read. */
    private static List<MarginDatabase.StoredClient> stored;

    private ClientStore() {
    }

    /** The saved clients in the order they were added. The list cannot be changed directly. */
    public static List<ClientProfile> all() {
        List<ClientProfile> list = new ArrayList<>();
        for (MarginDatabase.StoredClient s : stored()) {
            list.add(s.profile);
        }
        return List.copyOf(list);
    }

    /** The old clients file in the user's home folder. It is read once, when the database is first opened. */
    public static Path file() {
        return Paths.get(System.getProperty("user.home"), LEGACY_FILE_NAME);
    }

    /** Tells the listener whenever the saved clients change. */
    public static void addChangeListener(Runnable listener) {
        listeners.add(listener);
    }

    /** Adds a client at the end of the list. The caller checks the name first. */
    public static synchronized void add(ClientProfile profile) throws SQLException {
        AppDatabase.get().addClient(profile);
        reloadAndNotify();
    }

    /** Replaces the client at this position in the list. Their issued documents are not changed. */
    public static synchronized void update(int index, ClientProfile profile) throws SQLException {
        AppDatabase.get().updateClient(idAt(index), profile);
        reloadAndNotify();
    }

    /** Removes the client at this position. Their issued documents are kept as they were shown. */
    public static synchronized void delete(int index) throws SQLException {
        AppDatabase.get().deleteClient(idAt(index));
        reloadAndNotify();
    }

    /** The database id of a saved client, or null when the profile is not one of them. */
    public static synchronized Long idOf(ClientProfile profile) {
        if (profile == null) {
            return null;
        }
        for (MarginDatabase.StoredClient s : stored()) {
            if (s.profile == profile) {
                return s.id;
            }
        }
        for (MarginDatabase.StoredClient s : stored()) {
            if (s.profile.name.equalsIgnoreCase(profile.name)) {
                return s.id;
            }
        }
        return null;
    }

    /**
     * Reads the clients from an old-style clients file. A missing file gives no clients. An entry that cannot be read
     * is counted in skipped; the others still load.
     */
    static Legacy readLegacy(Path file) {
        Properties p = new Properties();
        try {
            if (file != null && Files.isRegularFile(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    p.load(in);
                }
            }
        } catch (IOException | RuntimeException ex) {
            return new Legacy(List.of(), 0);
        }
        List<ClientProfile> list = new ArrayList<>();
        int skipped = 0;
        int count = count(p.getProperty("count"));
        for (int i = 0; i < count; i++) {
            // Every entry has at least one property, so an index past the property count has no entry at all
            if (i >= p.size()) {
                skipped += count - i;
                break;
            }
            String prefix = "client." + i + ".";
            try {
                ContactDetails contact = new ContactDetails(
                        p.getProperty(prefix + "street"),
                        p.getProperty(prefix + "suburb"),
                        p.getProperty(prefix + "postal"),
                        p.getProperty(prefix + "phone"),
                        p.getProperty(prefix + "email"),
                        p.getProperty(prefix + "vatNumber"));
                list.add(new ClientProfile(
                        p.getProperty(prefix + "name"),
                        contact,
                        Boolean.parseBoolean(p.getProperty(prefix + "chargesVat")),
                        decimal(p.getProperty(prefix + "rate")),
                        decimal(p.getProperty(prefix + "markup"))));
            } catch (RuntimeException ex) {
                skipped++;
            }
        }
        return new Legacy(List.copyOf(list), skipped);
    }

    /** Checks that no other client has this name (names are compared without regard to capitals). */
    public static void checkUnique(List<ClientProfile> list, String name, int ignoreIndex)
            throws InputParser.InvalidInputException {
        for (int i = 0; i < list.size(); i++) {
            if (i != ignoreIndex && list.get(i).name.equalsIgnoreCase(name.trim())) {
                throw new InputParser.InvalidInputException("There is already a client called "
                        + list.get(i).name + ". Use a different name, or edit that client.");
            }
        }
    }

    /** The quotations and invoices issued to the client at this position in the list, newest first. */
    public static synchronized List<MarginDatabase.DocumentRow> documentsFor(int index) throws SQLException {
        return AppDatabase.get().documentsFor(idAt(index));
    }

    /**
     * The documents whose client has been deleted, or that were typed in without a saved client, grouped by the
     * name they were issued under.
     */
    public static synchronized List<MarginDatabase.DocumentRow> documentsWithoutClient() throws SQLException {
        return AppDatabase.get().documentsWithoutClient();
    }

    /** Tells the listeners that the documents have changed, for example after one is issued. */
    public static void announceChange() {
        for (Runnable listener : listeners) {
            listener.run();
        }
    }

    /** Reads the clients from the database again and tells the listeners. Used by the Refresh button and after a restore. */
    public static synchronized void refresh() {
        try {
            stored = AppDatabase.get().clients();
            Notices.clearOnce("clients-read");
        } catch (SQLException | RuntimeException ex) {
            // The list shown stays as it was, and the person is told why
            Notices.addOnce("clients-read", "The saved clients could not be read from the database, so the list may be "
                    + "out of date. Press Refresh (F5), or restart MarginIQ Suite. (" + ex.getMessage() + ")");
        }
        announceChange();
    }

    /** Forgets the list read so far, so the next use reads it again. Used while a restore replaces the database. */
    public static synchronized void forget() {
        stored = null;
    }

    /** The saved clients from the database, read the first time they are needed. */
    private static synchronized List<MarginDatabase.StoredClient> stored() {
        if (stored == null) {
            try {
                stored = AppDatabase.get().clients();
            } catch (SQLException ex) {
                // The database could not be opened. The list is empty for now, and the person is told why.
                Notices.addOnce("clients-read", "The saved clients could not be read from the database. Press Refresh (F5), "
                        + "or restart MarginIQ Suite. (" + ex.getMessage() + ")");
                return List.of();
            }
        }
        return stored;
    }

    private static long idAt(int index) throws SQLException {
        List<MarginDatabase.StoredClient> list = stored();
        if (index < 0 || index >= list.size()) {
            throw new SQLException("That client is no longer in the list.");
        }
        return list.get(index).id;
    }

    private static void reloadAndNotify() throws SQLException {
        stored = AppDatabase.get().clients();
        for (Runnable listener : listeners) {
            listener.run();
        }
    }

    private static int count(String raw) {
        try {
            return Math.max(0, Integer.parseInt(raw == null ? "" : raw.trim()));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static BigDecimal decimal(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
