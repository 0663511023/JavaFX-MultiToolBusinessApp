package application;

/**
 * Reads every saved value again from the database, so the screens show the latest data without a restart. Used by
 * the Refresh button and after a restore. Each step is separate, so a step that fails does not stop the others.
 */
public final class DataRefresh {

    private DataRefresh() {
    }

    /** Reads the settings, text size, branding, clients, pinned calculations and history again. */
    public static void reloadAll() {
        step(AppSettings::reload);
        step(FontSettings::reload);
        step(Branding::reload);
        step(ClientStore::refresh);
        step(PinStore::reload);
        step(BusinessHistory::reload);
        // Whatever this refresh applied is no longer waiting, so the banner is redrawn
        step(PendingChanges::notifyChanged);
    }

    private static void step(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ex) {
            Notices.add("Part of the app could not be refreshed: " + ex.getMessage());
        }
    }
}
