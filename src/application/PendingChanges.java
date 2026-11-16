package application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Keeps track of saved settings that a screen has not picked up yet, so the app can say what is still waiting:
 * a Refresh (F5), or a restart. A change waits when the saved value no longer matches the value the screen last
 * applied. Pure logic, no JavaFX.
 */
public final class PendingChanges {

    /** What the user has to do for a waiting change. */
    public enum Action {
        REFRESH,
        RESTART
    }

    /** One tracked setting: what the user must do, how it is named, the value applied, and how to read it now. */
    private static final class Entry {
        final Action action;
        final String label;
        final Supplier<String> saved;
        final String applied;

        Entry(Action action, String label, Supplier<String> saved, String applied) {
            this.action = action;
            this.label = label;
            this.saved = saved;
            this.applied = applied;
        }
    }

    private static final Map<String, Entry> entries = new LinkedHashMap<>();
    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    private PendingChanges() {
    }

    /**
     * Records that a screen has applied the saved value now. Calling it again records the new value, which is how a
     * refresh or a restart clears the waiting change.
     */
    public static synchronized void applied(String key, Action action, String label, Supplier<String> saved) {
        entries.put(key, new Entry(action, label, saved, String.valueOf(saved.get())));
    }

    /** The names of the changes that are waiting for this action, in the order they were recorded. */
    public static synchronized List<String> waitingFor(Action action) {
        List<String> waiting = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (entry.action == action && !Objects.equals(entry.applied, String.valueOf(entry.saved.get()))) {
                waiting.add(entry.label);
            }
        }
        return waiting;
    }

    /** Told when the waiting changes may have changed, so the banner can be redrawn. */
    public static void addChangeListener(Runnable listener) {
        listeners.add(listener);
    }

    public static void notifyChanged() {
        for (Runnable listener : listeners) {
            listener.run();
        }
    }
}
