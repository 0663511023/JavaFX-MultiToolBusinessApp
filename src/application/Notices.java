package application;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Messages about something that did not work, which the person must see. Code that runs without a screen, such as
 * the database opening or a background save, adds a message here; the screen shows it as a popup. Messages are kept
 * until they are taken, so none is lost if no screen was listening at the time. Pure logic with no JavaFX.
 */
public final class Notices {

    private static final List<String> pending = new ArrayList<>();
    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    /** The keys of problems already reported and not yet put right. See addOnce. */
    private static final Set<String> reported = new HashSet<>();

    private Notices() {
    }

    /** Adds a message and tells the listeners, which may be on another thread. */
    public static void add(String message) {
        synchronized (pending) {
            pending.add(message);
        }
        for (Runnable listener : listeners) {
            listener.run();
        }
    }

    /** Takes every message waiting so far, in the order they were added. */
    public static List<String> take() {
        synchronized (pending) {
            List<String> out = List.copyOf(pending);
            pending.clear();
            return out;
        }
    }

    /**
     * Adds a message for a problem that may repeat, such as a save that fails each time. The message is added once,
     * and not again until clearOnce is called for the same key, so the person is not shown the same popup over and over.
     */
    public static void addOnce(String key, String message) {
        boolean first;
        synchronized (reported) {
            first = reported.add(key);
        }
        if (first) {
            add(message);
        }
    }

    /** Called once the problem behind this key has been put right, so a new occurrence is reported again. */
    public static void clearOnce(String key) {
        synchronized (reported) {
            reported.remove(key);
        }
    }

    /** Tells the listener when a message is added. */
    public static void addListener(Runnable listener) {
        listeners.add(listener);
    }
}
