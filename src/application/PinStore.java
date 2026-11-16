package application;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The calculations pinned to the Home page. A pin keeps its own copy of the saved history line, so it still
 * opens if that history entry is later deleted or aged out by the retention setting. The pins are kept in the
 * MarginIQ database. Pure logic with no JavaFX.
 */
public final class PinStore {

    public static final int MAX_PINS = 20;

    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    /** False when the pins could not be read from the database. Nothing is saved then, so the pins are not overwritten. */
    private static volatile boolean loaded;
    private static volatile List<String> pins = readPins();

    private PinStore() {
    }

    /** Reads the pinned lines from the database. */
    private static List<String> readPins() {
        try {
            List<String> lines = AppDatabase.get().pinnedLines();
            loaded = true;
            Notices.clearOnce("pins-read");
            return List.copyOf(lines);
        } catch (SQLException | RuntimeException ex) {
            Notices.addOnce("pins-read", "The pinned calculations could not be read from the database, so they cannot "
                    + "be changed until you press Refresh (F5), or restart MarginIQ Suite. (" + ex.getMessage() + ")");
            loaded = false;
            return List.of();
        }
    }

    /** The pinned history lines, in the order they were pinned. */
    public static List<String> all() {
        return pins;
    }

    public static boolean isPinned(String raw) {
        return pins.contains(raw);
    }

    /** Tells the listener whenever the pinned calculations change. */
    public static void addChangeListener(Runnable listener) {
        listeners.add(listener);
    }

    /** Pins a saved history line. Pinning the same line twice does nothing. */
    public static void add(String raw) throws IOException, InputParser.InvalidInputException {
        if (raw == null || raw.isBlank()) {
            throw new InputParser.InvalidInputException("Select a saved calculation to pin.");
        }
        if (isPinned(raw)) {
            return;
        }
        if (pins.size() >= MAX_PINS) {
            throw new InputParser.InvalidInputException("You can pin up to " + MAX_PINS
                    + " calculations. Unpin one first.");
        }
        List<String> next = new ArrayList<>(pins);
        next.add(raw);
        save(next);
    }

    /** Removes a pin. A line that is not pinned is ignored. */
    public static void remove(String raw) throws IOException {
        if (!isPinned(raw)) {
            return;
        }
        List<String> next = new ArrayList<>(pins);
        next.remove(raw);
        save(next);
    }

    /** Stops changes to the pins until they are read again. Used while a restore replaces the database. */
    public static synchronized void forgetStorage() {
        loaded = false;
    }

    /** Reads the pins from the database again and tells the listeners. Used by the Refresh button and after a restore. */
    public static synchronized void reload() {
        pins = readPins();
        for (Runnable listener : listeners) {
            listener.run();
        }
    }

    private static void save(List<String> list) throws IOException {
        if (!loaded) {
            throw new IOException("The pinned calculations could not be read from the database, so they cannot be "
                    + "changed until you press Refresh (F5), or restart MarginIQ Suite.");
        }
        try {
            AppDatabase.get().replacePins(list);
        } catch (SQLException ex) {
            throw new IOException("Could not save the pinned calculations: " + ex.getMessage(), ex);
        }
        pins = List.copyOf(list);
        for (Runnable listener : listeners) {
            listener.run();
        }
    }
}
