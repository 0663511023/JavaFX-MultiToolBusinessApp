package application;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Central place for the app's constants and the user's saved settings.
 * Tax tables and divisors are fixed here and updated when SARS or the couriers change.
 * The user's own settings are kept in the MarginIQ database, and every value is checked on the way in.
 */
public final class AppSettings {

    private AppSettings() {}

    // ── VAT ──────────────────────────────────────────────────────────────────
    /** The VAT rate the app starts with. The user can change it in Settings. */
    public static final BigDecimal DEFAULT_VAT_PERCENT = new BigDecimal("15");

    // ── Freelancer assumptions ────────────────────────────────────────────────
    public static final int BILLABLE_WEEKS_PER_YEAR = 48;

    // ── Volumetric divisors ───────────────────────────────────────────────────
    public static final int DIVISOR_5000 = 5000;   // standard / most SA couriers
    public static final int DIVISOR_4000 = 4000;   // DHL / premium couriers

    // ── SARS 2026/27 (Year of Assessment 2027) ───────────────────────────────
    public static final double PRIMARY_REBATE   = 17_820.0;
    public static final double SECONDARY_REBATE =  9_765.0;  // 65+
    public static final double TERTIARY_REBATE  =  3_249.0;  // 75+

    public static final double THRESHOLD_UNDER_65 =  99_000.0;
    public static final double THRESHOLD_65_TO_74 = 153_250.0;
    public static final double THRESHOLD_75_PLUS  = 171_300.0;

    // Tax brackets: each row is { lower bound, cumulative tax to lower bound, marginal rate }
    public static final double[][] TAX_BRACKETS = {
        {         0.0,       0.0, 0.18 },
        {   245_100.0,  44_118.0, 0.26 },
        {   383_100.0,  79_998.0, 0.31 },
        {   530_200.0, 125_599.0, 0.36 },
        {   695_800.0, 185_215.0, 0.39 },
        {   887_000.0, 259_783.0, 0.41 },
        { 1_878_600.0, 666_339.0, 0.45 },
    };

    /** Calculate raw tax-before-rebate from annual taxable income. */
    public static double calculateTaxBracket(double income) {
        for (int i = TAX_BRACKETS.length - 1; i >= 0; i--) {
            if (income > TAX_BRACKETS[i][0]) {
                return TAX_BRACKETS[i][1] + (income - TAX_BRACKETS[i][0]) * TAX_BRACKETS[i][2];
            }
        }
        return income * 0.18;
    }

    public static double getRebate(String ageGroup) {
        return switch (ageGroup) {
            case "65 to 74"  -> PRIMARY_REBATE + SECONDARY_REBATE;
            case "75+"       -> PRIMARY_REBATE + SECONDARY_REBATE + TERTIARY_REBATE;
            default          -> PRIMARY_REBATE;
        };
    }

    public static double getThreshold(String ageGroup) {
        return switch (ageGroup) {
            case "65 to 74"  -> THRESHOLD_65_TO_74;
            case "75+"       -> THRESHOLD_75_PLUS;
            default          -> THRESHOLD_UNDER_65;
        };
    }

    // ── User settings ────────────────────────────────────────────────────────

    /** The page the app opens on by default. */
    public static final String HOME_VIEW = "Home";

    /** How long calculation history is kept, in days. 0 keeps everything. */
    public static final int[] RETENTION_DAYS = {0, 30, 90, 365};

    public static final BigDecimal DEFAULT_LOAN_RATE = new BigDecimal("11.5");
    public static final BigDecimal DEFAULT_LOAN_YEARS = new BigDecimal("20");

    /** How amounts are shown. South African style (1 234,56) is the default; standard is 1,234.56. */
    public static final String DEFAULT_NUMBER_STYLE = Money.SA;

    /** The narrowest the sidebar can be: the icons only. */
    public static final int MIN_SIDEBAR_WIDTH = 76;
    /** The widest the sidebar can be, and the width it starts with. */
    public static final int MAX_SIDEBAR_WIDTH = 350;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private static volatile boolean darkTheme = false;
    private static volatile BigDecimal vatPercent = DEFAULT_VAT_PERCENT;
    private static volatile BigDecimal loanRate = DEFAULT_LOAN_RATE;
    private static volatile BigDecimal loanYears = DEFAULT_LOAN_YEARS;
    private static volatile String startupView = HOME_VIEW;
    private static volatile boolean sidebarCollapsed = false;
    private static volatile String exportFolder = "";
    private static volatile int retentionDays = 0;
    private static volatile String numberStyle = DEFAULT_NUMBER_STYLE;
    private static volatile int sidebarWidth = MAX_SIDEBAR_WIDTH;

    /** Told after every save, so open pages can follow the new settings. */
    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    /**
     * True once the saved settings have been read from the database. Until then nothing is written, so a read that
     * failed cannot overwrite the real values with the defaults.
     */
    private static volatile boolean readFromDatabase;

    static {
        reload();
    }

    // ── Reading the saved settings ───────────────────────────────────────────

    /**
     * Reads the saved settings from the database again, then tells the listeners. A missing or invalid value falls
     * back to its default. Used at startup, and by the Refresh button.
     */
    public static void reload() {
        Map<String, String> saved;
        try {
            saved = AppDatabase.get().settingGroup("settings");
            readFromDatabase = true;
            Notices.clearOnce("settings-read");
        } catch (SQLException | RuntimeException ex) {
            Notices.addOnce("settings-read", "The settings could not be read from the database, so the default settings "
                    + "are in use, and changes will not be saved until you press Refresh (F5), or restart MarginIQ Suite. ("
                    + ex.getMessage() + ")");
            saved = Map.of();
            readFromDatabase = false;
        }
        applyValues(saved);
        for (Runnable listener : listeners) {
            listener.run();
        }
    }

    /** Applies saved values. A missing or invalid value falls back to its default. */
    private static void applyValues(Map<String, String> p) {
        darkTheme = "dark".equals(p.get("theme"));
        vatPercent = orDefault(() -> checkVatPercent(p.get("vatPercent")), DEFAULT_VAT_PERCENT);
        loanRate = orDefault(() -> checkLoanRate(p.get("loanRatePercent")), DEFAULT_LOAN_RATE);
        loanYears = orDefault(() -> checkLoanYears(p.get("loanYears")), DEFAULT_LOAN_YEARS);
        startupView = orDefault(() -> text(p.get("startupView"), HOME_VIEW), HOME_VIEW);
        sidebarCollapsed = "collapsed".equals(p.get("sidebar"));
        exportFolder = orDefault(() -> checkExportFolder(p.get("exportFolder")), "");
        retentionDays = orDefault(
                () -> checkRetention(Integer.parseInt(p.getOrDefault("retentionDays", "0").trim())), 0);
        numberStyle = orDefault(() -> checkNumberStyle(p.get("numberStyle")), DEFAULT_NUMBER_STYLE);
        sidebarWidth = orDefault(
                () -> clampSidebarWidth(Integer.parseInt(p.getOrDefault("sidebarWidth", "").trim())),
                MAX_SIDEBAR_WIDTH);
    }

    @FunctionalInterface
    private interface Check<T> {
        T get() throws InputParser.InvalidInputException;
    }

    private static <T> T orDefault(Check<T> check, T fallback) {
        try {
            T value = check.get();
            return value == null ? fallback : value;
        } catch (InputParser.InvalidInputException | RuntimeException ex) {
            return fallback;
        }
    }

    private static String text(String raw, String fallback) {
        return raw == null || raw.isBlank() ? fallback : raw.trim();
    }

    // ── Checks, shared by the database reader and the Settings page ─────────

    /** A VAT percentage such as "15" or "15%". Must be from 0 up to, but not including, 100. */
    public static BigDecimal checkVatPercent(String raw) throws InputParser.InvalidInputException {
        BigDecimal value = InputParser.parseDecimal(required(raw, "VAT rate"), "VAT rate");
        if (value.signum() < 0 || value.compareTo(HUNDRED) >= 0) {
            throw new InputParser.InvalidInputException("VAT rate must be from 0 up to, but not including, 100.");
        }
        return value.stripTrailingZeros();
    }

    /** A default interest rate in percent. Must be from 0 to 100. */
    public static BigDecimal checkLoanRate(String raw) throws InputParser.InvalidInputException {
        BigDecimal value = InputParser.parseDecimal(required(raw, "Interest rate"), "Interest rate");
        if (value.signum() < 0 || value.compareTo(HUNDRED) > 0) {
            throw new InputParser.InvalidInputException("Interest rate must be from 0 to 100%.");
        }
        return value.stripTrailingZeros();
    }

    /** A default loan term in years. Must be more than 0 and at most 100, in whole months. */
    public static BigDecimal checkLoanYears(String raw) throws InputParser.InvalidInputException {
        BigDecimal value = InputParser.parseDecimal(required(raw, "Loan term"), "Loan term");
        if (value.signum() <= 0 || value.compareTo(HUNDRED) > 0) {
            throw new InputParser.InvalidInputException("Loan term must be more than 0 and at most 100 years.");
        }
        if (value.multiply(BigDecimal.valueOf(12)).stripTrailingZeros().scale() > 0) {
            throw new InputParser.InvalidInputException(
                    "Loan term must be a whole number of months, for example 20 or 20.25 years.");
        }
        return value.stripTrailingZeros();
    }

    /** The number style: Money.SA or Money.STANDARD. */
    public static String checkNumberStyle(String raw) throws InputParser.InvalidInputException {
        String value = required(raw, "Number style");
        if (Money.SA.equals(value) || Money.STANDARD.equals(value)) {
            return value;
        }
        throw new InputParser.InvalidInputException("Choose the South African or the standard number style.");
    }

    /** An export folder. Blank means the home folder. Otherwise it must be a folder that exists. */
    public static String checkExportFolder(String raw) throws InputParser.InvalidInputException {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String folder = raw.trim();
        try {
            if (Files.isDirectory(Paths.get(folder))) {
                return folder;
            }
        } catch (InvalidPathException ex) {
            // Handled below
        }
        throw new InputParser.InvalidInputException(
                "That folder does not exist. Pick an existing folder, or leave the field empty.");
    }

    /** A history retention period: one of the choices offered, in days. */
    public static int checkRetention(int days) throws InputParser.InvalidInputException {
        for (int choice : RETENTION_DAYS) {
            if (choice == days) {
                return days;
            }
        }
        throw new InputParser.InvalidInputException("Choose one of the history retention periods.");
    }

    /** Keeps a sidebar width within the range from the icons-only width up to the full width. */
    public static int clampSidebarWidth(int width) {
        return Math.max(MIN_SIDEBAR_WIDTH, Math.min(MAX_SIDEBAR_WIDTH, width));
    }

    private static String required(String raw, String name) throws InputParser.InvalidInputException {
        if (raw == null || raw.isBlank()) {
            throw new InputParser.InvalidInputException(name + " is required.");
        }
        return raw.trim();
    }

    // ── Reading the current values ───────────────────────────────────────────

    public static boolean isDarkTheme() {
        return darkTheme;
    }

    /** The VAT rate as a fraction, such as 0.15. */
    public static BigDecimal vatRate() {
        return vatPercent.movePointLeft(2);
    }

    /** The VAT rate as a percentage for display, such as "15". */
    public static String vatPercentText() {
        return Money.text(vatPercent.toPlainString());
    }

    public static String loanRateText() {
        return Money.text(loanRate.toPlainString());
    }

    public static String loanYearsText() {
        return Money.text(loanYears.toPlainString());
    }

    /** The number style: Money.SA or Money.STANDARD. */
    public static String numberStyle() {
        return numberStyle;
    }

    public static String startupView() {
        return startupView;
    }

    public static boolean sidebarCollapsed() {
        return sidebarCollapsed;
    }

    /** The width of the sidebar the last time it was set, from MIN_SIDEBAR_WIDTH up to MAX_SIDEBAR_WIDTH. */
    public static int sidebarWidth() {
        return sidebarWidth;
    }

    /** The default export folder, or an empty string for the home folder. */
    public static String exportFolder() {
        return exportFolder;
    }

    public static int retentionDays() {
        return retentionDays;
    }

    // ── Changing settings ────────────────────────────────────────────────────

    /** Tells the listener whenever settings are saved. */
    public static void addChangeListener(Runnable listener) {
        listeners.add(listener);
    }

    /**
     * Saves every setting at once, then tells the listeners. The values must already be checked.
     * If the database cannot be written, nothing changes.
     */
    public static void update(boolean dark, BigDecimal vat, BigDecimal rate, BigDecimal years,
                              String startup, boolean collapsed, String folder, int retention,
                              String style) throws IOException {
        write(valuesFor(dark, vat, rate, years, startup, collapsed, folder, retention, style, sidebarWidth));

        darkTheme = dark;
        vatPercent = vat;
        loanRate = rate;
        loanYears = years;
        startupView = startup;
        sidebarCollapsed = collapsed;
        exportFolder = folder;
        retentionDays = retention;
        numberStyle = style;
        for (Runnable listener : listeners) {
            listener.run();
        }
    }

    /**
     * Saves the sidebar width, kept within the allowed range. Only the width changes, and nothing is announced,
     * because the sidebar is already showing it. If the database cannot be written, the width does not change.
     */
    public static void saveSidebarWidth(int width) throws IOException {
        int clamped = clampSidebarWidth(width);
        write(valuesFor(darkTheme, vatPercent, loanRate, loanYears, startupView, sidebarCollapsed, exportFolder,
                retentionDays, numberStyle, clamped));
        sidebarWidth = clamped;
    }

    private static Map<String, String> valuesFor(boolean dark, BigDecimal vat, BigDecimal rate, BigDecimal years,
                                                 String startup, boolean collapsed, String folder, int retention,
                                                 String style, int width) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("theme", dark ? "dark" : "light");
        p.put("vatPercent", vat.toPlainString());
        p.put("loanRatePercent", rate.toPlainString());
        p.put("loanYears", years.toPlainString());
        p.put("startupView", startup);
        p.put("sidebar", collapsed ? "collapsed" : "expanded");
        p.put("exportFolder", folder);
        p.put("retentionDays", String.valueOf(retention));
        p.put("numberStyle", style);
        p.put("sidebarWidth", String.valueOf(width));
        return p;
    }

    /** Writes the whole settings group in one transaction. */
    private static void write(Map<String, String> values) throws IOException {
        if (!readFromDatabase) {
            throw new IOException("The saved settings could not be read, so nothing has been changed. "
                    + "Press Refresh (F5), or restart MarginIQ Suite.");
        }
        try {
            AppDatabase.get().replaceSettingGroup("settings", values);
        } catch (SQLException ex) {
            throw new IOException("Could not save the settings: " + ex.getMessage(), ex);
        }
    }
}
