package application;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * The zip that holds a backup. A new backup holds one file: a consistent copy of the MarginIQ database, which has
 * the clients, history, archive, pins, branding, logo and settings in it. Backups made by earlier versions also
 * hold the old files, so those names are still accepted on restore. On restore only these known names are
 * accepted, so a crafted backup cannot write anywhere else. Pure logic with no JavaFX.
 */
public final class BackupArchive {

    /** The old files that an earlier backup may hold. They are accepted on restore, but never written now. */
    static final List<String> OLD_FILES = List.of(
            "marginiq_history.json",
            "marginiq_history_archive.txt",
            "marginiq_clients.properties",
            "marginiq_pins.properties",
            "marginiq_branding.properties",
            "marginiq_settings.properties",
            "marginiq_font.properties");

    /** The database. A backup holds a consistent copy made by SQLite, which is passed in; it is never read from the folder. */
    static final String DATABASE_NAME = "marginiq.db";

    /** Refuses a backup that would be unreasonably large, so a damaged or hostile file cannot fill the disk. */
    static final long MAX_TOTAL_BYTES = 200L * 1024 * 1024;

    private BackupArchive() {
    }

    /** True for a name a backup may hold: the database, an old file from an earlier backup, or an old logo copy. */
    static boolean isBackupName(String name) {
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            return false;
        }
        return name.equals(DATABASE_NAME) || OLD_FILES.contains(name) || name.matches("marginiq_logo\\.(png|jpg)");
    }

    /**
     * Checks the database copy inside a backup before anything is replaced. It must open, pass SQLite's own quick
     * check, and be from this version or an older one. Throws IOException with the reason if not.
     */
    static void checkDatabaseCopy(byte[] database) throws IOException {
        Path temp = Files.createTempFile("miq-check-", ".db");
        try {
            Files.write(temp, database);
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + temp.toAbsolutePath());
                 Statement s = c.createStatement()) {
                try (ResultSet rs = s.executeQuery("PRAGMA quick_check")) {
                    if (!rs.next() || !"ok".equalsIgnoreCase(rs.getString(1))) {
                        throw new IOException("The database in the backup is damaged, so it was not restored.");
                    }
                }
                int version = 0;
                try (ResultSet rs = s.executeQuery("PRAGMA user_version")) {
                    if (rs.next()) {
                        version = rs.getInt(1);
                    }
                }
                if (version > MarginDatabase.SCHEMA_VERSION) {
                    throw new IOException("This backup was made by a newer version of MarginIQ Suite, "
                            + "so it was not restored.");
                }
            } catch (SQLException ex) {
                throw new IOException("The database in the backup cannot be read, so it was not restored: "
                        + ex.getMessage(), ex);
            }
        } finally {
            Files.deleteIfExists(temp);
            Files.deleteIfExists(Paths.get(temp.toAbsolutePath() + "-journal"));
            Files.deleteIfExists(Paths.get(temp.toAbsolutePath() + "-wal"));
            Files.deleteIfExists(Paths.get(temp.toAbsolutePath() + "-shm"));
        }
    }

    /** Zips a consistent copy of the database, which is passed in, as marginiq.db. */
    public static byte[] zip(Path databaseCopy) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(DATABASE_NAME));
            zip.write(Files.readAllBytes(databaseCopy));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    /**
     * Reads a backup zip. Any entry that is not a known backup name refuses the whole backup, so nothing is
     * restored from a file that does not look like one of ours.
     */
    public static Map<String, byte[]> unzip(byte[] zipBytes) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (!isBackupName(name)) {
                    throw new IOException("The backup holds an unexpected file, so it was not restored: " + name);
                }
                // Reads at most one byte past what is still allowed, so an oversized entry is refused without being held
                byte[] content = zip.readNBytes((int) (MAX_TOTAL_BYTES - total + 1));
                total += content.length;
                if (total > MAX_TOTAL_BYTES) {
                    throw new IOException("The backup is too large to be a MarginIQ Suite backup.");
                }
                files.put(name, content);
            }
        }
        if (files.isEmpty()) {
            throw new IOException("The backup is empty.");
        }
        return files;
    }
}
