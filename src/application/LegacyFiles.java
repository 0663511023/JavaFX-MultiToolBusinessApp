package application;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Reads the files the app kept before everything moved into the database. They are read once, when the database
 * is first opened, brought across, and then deleted. Pure logic with no JavaFX.
 */
final class LegacyFiles {

    private static final String HISTORY_NAME = "marginiq_history.json";
    private static final String PINS_NAME = "marginiq_pins.properties";
    private static final String SETTINGS_NAME = "marginiq_settings.properties";
    private static final String FONT_NAME = "marginiq_font.properties";
    private static final String BRANDING_NAME = "marginiq_branding.properties";
    private static final String ARCHIVE_NAME = "marginiq_history_archive.txt";
    private static final String LOGO_PREFIX = "marginiq_logo.";
    private static final List<String> LOGO_EXTENSIONS = List.of("png", "jpg", "jpeg");

    private LegacyFiles() {
    }

    /** The old history file in the user's home folder. */
    static Path historyFile() {
        return home().resolve(HISTORY_NAME);
    }

    /** The old pins file in the user's home folder. */
    static Path pinsFile() {
        return home().resolve(PINS_NAME);
    }

    /** The old settings file in the user's home folder. */
    static Path settingsFile() {
        return home().resolve(SETTINGS_NAME);
    }

    /** The old font file in the user's home folder. */
    static Path fontFile() {
        return home().resolve(FONT_NAME);
    }

    /** The old branding file in the user's home folder. */
    static Path brandingFile() {
        return home().resolve(BRANDING_NAME);
    }

    /** The old history archive, one entry per line, in the user's home folder. */
    static Path archiveFile() {
        return home().resolve(ARCHIVE_NAME);
    }

    /** The lines of an old history file, newest first, and how many lines of it could not be read. */
    record History(List<String> lines, int skipped) {
    }

    /**
     * The lines in an old history file, newest first. A missing file gives no lines. A line that is neither a history
     * entry nor JSON brackets is counted as skipped, so the caller keeps the file instead of losing it. Throws when the
     * file is not readable text, so the caller can keep it aside.
     */
    static History history(Path file) throws IOException {
        List<String> loaded = new ArrayList<>();
        int skipped = 0;
        if (!Files.isRegularFile(file)) {
            return new History(loaded, 0);
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.endsWith(",")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
            }
            if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                loaded.add(unescapeJson(trimmed.substring(1, trimmed.length() - 1)));
            } else if (!trimmed.isEmpty() && !List.of("[", "]", "{", "}", "[]", "{}").contains(trimmed)) {
                skipped++;
            }
        }
        return new History(loaded, skipped);
    }

    /** The pinned lines in an old pins file, in order. A missing or unreadable file gives an empty list. */
    static List<String> pins(Path file) {
        Properties p = new Properties();
        try {
            if (Files.isRegularFile(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    p.load(in);
                }
            }
        } catch (IOException | RuntimeException ex) {
            return List.of();
        }
        int count;
        try {
            count = Integer.parseInt(p.getProperty("count", "0").trim());
        } catch (NumberFormatException ex) {
            count = 0;
        }
        List<String> list = new ArrayList<>();
        // Every pin is brought across. The limit on pins applies only to pinning new calculations, so none is lost here.
        for (int i = 0; i < count; i++) {
            String raw = p.getProperty("pin." + i);
            if (raw != null && !raw.isBlank() && !list.contains(raw)) {
                list.add(raw);
            }
        }
        return List.copyOf(list);
    }

    /**
     * The keys and values in an old properties file. A missing file gives an empty map. Throws when the file cannot
     * be read, so the caller can keep it aside.
     */
    static Map<String, String> properties(Path file) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) {
            return values;
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        } catch (IllegalArgumentException ex) {
            throw new IOException("The file is not a settings file: " + ex.getMessage(), ex);
        }
        for (String key : p.stringPropertyNames()) {
            values.put(key, p.getProperty(key));
        }
        return values;
    }

    /** The non-blank lines of an old text file, as they were written. A missing file gives an empty list. */
    static List<String> lines(Path file) throws IOException {
        List<String> list = new ArrayList<>();
        if (!Files.isRegularFile(file)) {
            return list;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                list.add(line);
            }
        }
        return list;
    }

    /**
     * The logo image from the old home folder: the file named in the old branding settings if it is still there,
     * otherwise marginiq_logo.png, .jpg or .jpeg. Returns null when there is no logo. Throws when a logo file
     * cannot be read.
     */
    static MarginDatabase.StoredLogo logo(String recordedPath) throws IOException {
        List<Path> candidates = new ArrayList<>();
        if (recordedPath != null && !recordedPath.isBlank()) {
            try {
                candidates.add(Paths.get(recordedPath.trim()));
            } catch (InvalidPathException ex) {
                // A path that cannot be used is ignored, and the default names are tried instead
            }
        }
        for (String extension : LOGO_EXTENSIONS) {
            candidates.add(home().resolve(LOGO_PREFIX + extension));
        }
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return new MarginDatabase.StoredLogo(candidate.getFileName().toString(),
                        Files.readAllBytes(candidate));
            }
        }
        return null;
    }

    /** Deletes the logo files that were kept in the home folder. Only the default names are removed. */
    static void deleteHomeLogos() {
        for (String extension : LOGO_EXTENSIONS) {
            try {
                Files.deleteIfExists(home().resolve(LOGO_PREFIX + extension));
            } catch (IOException ex) {
                // It stays where it is. Its contents are already in the database.
            }
        }
    }

    private static Path home() {
        return Paths.get(System.getProperty("user.home"));
    }

    /** Reverses the escaping the old history file used, reading each escape exactly once. */
    private static String unescapeJson(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(++i);
                switch (next) {
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    default -> out.append(next); // covers \\ and \"
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
