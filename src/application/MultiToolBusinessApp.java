package application;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import javafx.application.Application;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;


public class MultiToolBusinessApp extends Application {
	private boolean isDark;

	@Override
	public void start(Stage stage) {
		// Settings are read from the user's home folder, and the theme is the first of them
		isDark = AppSettings.isDarkTheme();

		BorderPane root = new BorderPane();
		root.getStyleClass().add(isDark ? "dark-theme" : "light-theme");

		Scene scene = new Scene(root, 1120, 720);

		BusinessTab bTab = new BusinessTab(isDark);
		root.setCenter(bTab.getRootPane());

		// The theme is changed in Settings. When it is saved, the whole suite follows.
		AppSettings.addChangeListener(() -> {
			boolean dark = AppSettings.isDarkTheme();
			if (dark != isDark) {
				isDark = dark;
				scene.getRoot().getStyleClass().removeAll("light-theme", "dark-theme");
				scene.getRoot().getStyleClass().add(isDark ? "dark-theme" : "light-theme");
				bTab.updateTheme(isDark);
			}
		});

		String cssPath = getClass().getResource("application.css") != null
					   ? getClass().getResource("application.css").toExternalForm()
					   : "application.css";
		scene.getStylesheets().add(cssPath);

		stage.setTitle("MarginIQ Suite");
		stage.getIcons().addAll(appIcons());
		stage.setMinWidth(1000);
		stage.setMinHeight(600);
		stage.setScene(scene);
		stage.show();
	}

	/** The app icon at several sizes, so the title bar and taskbar can each pick the sharpest one. */
	private static List<Image> appIcons() {
		List<Image> icons = new ArrayList<>();
		for (int size : new int[] {16, 32, 64, 128, 256}) {
			try (InputStream in = MultiToolBusinessApp.class.getResourceAsStream("marginiq-icon-" + size + ".png")) {
				if (in != null) {
					icons.add(new Image(in));
				}
			} catch (IOException ex) {
				// A missing or unreadable icon is not an error: the window opens with the default one
			}
		}
		return icons;
	}

	public static void main(String[] args) {
		// One fixed number style everywhere (1,234.56), so prices, history and inputs agree
		java.util.Locale.setDefault(java.util.Locale.ENGLISH);
		launch(args);
	}
}
