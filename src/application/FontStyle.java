package application;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import javafx.scene.Scene;

/**
 * Puts the text overrides from FontCss into a scene. A new file is written each time the settings change, under a
 * new name, so JavaFX never reuses an old copy. The overrides go after the app's own stylesheet, so they win
 * wherever both set the same font.
 */
final class FontStyle {

    /** Part of every override file's name, so an earlier copy can be found and replaced. */
    private static final String MARK = "marginiq-text-";

    private static volatile String currentUrl;

    private FontStyle() {
    }

    /** The override stylesheet for the current settings, or null if it could not be written. Dialogs add it too. */
    static String currentUrl() {
        return currentUrl;
    }

    /** Writes the overrides for the current settings and puts them last in the scene's stylesheets. */
    static synchronized void apply(Scene scene) {
        try {
            Path file = Files.createTempFile(MARK, ".css");
            file.toFile().deleteOnExit();
            Files.writeString(file, FontCss.build(readBase(), FontSettings.scale(), FontSettings.familyCss()),
                    StandardCharsets.UTF_8);
            currentUrl = file.toUri().toURL().toExternalForm();
        } catch (IOException | RuntimeException ex) {
            // The app keeps its own sizes if the overrides cannot be written. Nothing else depends on them.
            currentUrl = null;
            Notices.addOnce("font-apply", "The text settings could not be applied: " + ex.getMessage());
        }
        if (scene == null) {
            return;
        }
        scene.getStylesheets().removeIf(sheet -> sheet.contains(MARK));
        if (currentUrl != null) {
            scene.getStylesheets().add(currentUrl);
        }
    }

    /** The app's own stylesheet, read from the same place the app loads it. */
    private static String readBase() {
        try (InputStream in = FontStyle.class.getResourceAsStream("application.css")) {
            return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return "";
        }
    }
}
