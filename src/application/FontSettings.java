package application;

import java.io.IOException;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The text size and typeface chosen in Settings. The size is a percentage of the normal size, kept between
 * MIN_PERCENT and MAX_PERCENT in steps of STEP. The typeface is one of a fixed list of six, all of which ship
 * with Windows. Icons, the sidebar and the Home cards follow the size. Saved in the MarginIQ database.
 * Pure logic with no JavaFX.
 */
public final class FontSettings {

    public static final int MIN_PERCENT = 80;
    public static final int MAX_PERCENT = 140;
    public static final int STEP = 5;
    public static final int DEFAULT_PERCENT = 100;

    /** The typefaces offered. The first is the app's own. Names shown to the user, and the names given to the stylesheet. */
    public static final String[] FAMILY_NAMES = {
        "Segoe UI (default)", "Calibri", "Arial", "Verdana", "Trebuchet MS", "Georgia"};
    public static final String[] FAMILY_CSS = {"Segoe UI", "Calibri", "Arial", "Verdana", "Trebuchet MS", "Georgia"};

    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    private static volatile int percent = DEFAULT_PERCENT;
    private static volatile int family = 0;

    static {
        reload();
    }

    private FontSettings() {
    }

    public static int percent() {
        return percent;
    }

    /** The text size as a multiplier: 1.0 is the normal size. */
    public static double scale() {
        return percent / 100.0;
    }

    public static int familyIndex() {
        return family;
    }

    public static String familyName() {
        return FAMILY_NAMES[family];
    }

    public static String familyCss() {
        return FAMILY_CSS[family];
    }

    /** Tells the listener whenever the text size or typeface is saved or read again. */
    public static void addChangeListener(Runnable listener) {
        listeners.add(listener);
    }

    /** A text size: a whole percentage from the minimum to the maximum, in steps of STEP. */
    public static int checkPercent(int value) throws InputParser.InvalidInputException {
        if (value < MIN_PERCENT || value > MAX_PERCENT) {
            throw new InputParser.InvalidInputException("The text size must be between " + MIN_PERCENT
                    + "% and " + MAX_PERCENT + "%.");
        }
        if ((value - MIN_PERCENT) % STEP != 0) {
            throw new InputParser.InvalidInputException("The text size moves in steps of " + STEP + "%.");
        }
        return value;
    }

    /** A typeface: its position in the list of six. */
    public static int checkFamily(int index) throws InputParser.InvalidInputException {
        if (index < 0 || index >= FAMILY_CSS.length) {
            throw new InputParser.InvalidInputException("Choose one of the typefaces in the list.");
        }
        return index;
    }

    /** Saves the text size and typeface, then applies them. Nothing changes if the save fails. */
    public static void update(int newPercent, int newFamily) throws IOException, InputParser.InvalidInputException {
        int p = checkPercent(newPercent);
        int f = checkFamily(newFamily);
        write(p, FAMILY_CSS[f]);
        apply(p, f);
    }

    /** Puts the text size and typeface back to the defaults. */
    public static void restoreDefaults() throws IOException {
        write(DEFAULT_PERCENT, FAMILY_CSS[0]);
        apply(DEFAULT_PERCENT, 0);
    }

    /** True once the saved text size has been read from the database. Until then nothing is written. */
    private static volatile boolean readFromDatabase;

    /** Reads the saved text size and typeface from the database again. A missing or invalid value gives the defaults. */
    public static void reload() {
        Map<String, String> saved;
        try {
            saved = AppDatabase.get().settingGroup("font");
            readFromDatabase = true;
            Notices.clearOnce("font-read");
        } catch (SQLException | RuntimeException ex) {
            Notices.addOnce("font-read", "The text size and typeface could not be read from the database, so the defaults "
                    + "are in use, and changes will not be saved until you press Refresh (F5), or restart MarginIQ Suite. ("
                    + ex.getMessage() + ")");
            saved = Map.of();
            readFromDatabase = false;
        }
        int p;
        try {
            p = checkPercent(Integer.parseInt(saved.getOrDefault("percent", "").trim()));
        } catch (NumberFormatException | InputParser.InvalidInputException ex) {
            p = DEFAULT_PERCENT;
        }
        int f = 0;
        String savedFamily = saved.getOrDefault("family", "").trim();
        for (int i = 0; i < FAMILY_CSS.length; i++) {
            if (FAMILY_CSS[i].equals(savedFamily)) {
                f = i;
            }
        }
        apply(p, f);
    }

    private static void apply(int newPercent, int newFamily) {
        percent = newPercent;
        family = newFamily;
        for (Runnable listener : listeners) {
            listener.run();
        }
    }

    /** Writes the text size and typeface to the database in one transaction. */
    static void write(int p, String familyCss) throws IOException {
        if (!readFromDatabase) {
            throw new IOException("The text size could not be read, so nothing has been changed. "
                    + "Press Refresh (F5), or restart MarginIQ Suite.");
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put("percent", String.valueOf(p));
        values.put("family", familyCss);
        try {
            AppDatabase.get().replaceSettingGroup("font", values);
        } catch (SQLException ex) {
            throw new IOException("Could not save the text size: " + ex.getMessage(), ex);
        }
    }
}
