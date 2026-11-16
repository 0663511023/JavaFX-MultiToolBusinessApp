package application;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.crypto.AEADBadTagException;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.DragEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

/**
 * The Backup & Restore card on the Settings page. A backup is one encrypted file that holds the history, clients,
 * pinned calculations, branding and settings. The backup password must meet every requirement when it is chosen,
 * and it is saved encrypted in the database so it can be shown again.
 */
final class BackupPanel {

    private static final String EXTENSION = ".miqbackup";
    /** Colours for a requirement that is met, and one that is not yet met. */
    private static final String MET_COLOUR = "#1b7f3b";
    private static final String TODO_COLOUR = "#b3261e";

    private BackupPanel() {
    }

    /** The card with its buttons and a status line. */
    static VBox card() {
        Label status = new Label();
        status.getStyleClass().add("info-note");
        status.setWrapText(true);
        status.setVisible(false);
        status.setManaged(false);

        Button backupBtn = new Button("Create Backup...");
        backupBtn.getStyleClass().add("button-primary");
        Button restoreBtn = new Button("Restore from Backup...");
        restoreBtn.getStyleClass().add("pill-button");
        Button savedBtn = new Button("Show Saved Password...");
        savedBtn.getStyleClass().add("pill-button");
        backupBtn.setOnAction(e -> backup(backupBtn.getScene().getWindow(), status));
        restoreBtn.setOnAction(e -> restore(restoreBtn.getScene().getWindow(), status));
        savedBtn.setOnAction(e -> showSaved(savedBtn.getScene().getWindow(), status));

        Label heading = new Label("BACKUP & RESTORE");
        heading.getStyleClass().add("card-section");
        VBox card = new VBox(12, heading,
                CalcLayout.info("A backup is one file, protected by a password. A new password must meet every "
                        + "requirement shown when you create the backup. The password is saved encrypted in MarginIQ "
                        + "Suite, so you can show it again with Show Saved Password. A backup cannot be opened without "
                        + "the password."),
                new HBox(10, backupBtn, restoreBtn, savedBtn),
                status);
        card.getStyleClass().add("result-card");
        card.setPadding(new Insets(18));
        return card;
    }

    private static void backup(Window owner, Label status) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save backup as");
        chooser.setInitialFileName("marginiq-backup-" + LocalDate.now() + EXTENSION);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("MarginIQ backup", "*" + EXTENSION));
        File chosen = chooser.showSaveDialog(owner);
        if (chosen == null) {
            return;
        }
        File file = chosen.getName().toLowerCase().endsWith(EXTENSION)
                ? chosen : new File(chosen.getPath() + EXTENSION);

        char[] password = askPassword(owner, true);
        if (password == null) {
            return;
        }
        // The password saved before this attempt, so it can be put back if this backup does not finish
        char[] previous;
        try {
            previous = SavedPassword.load();
        } catch (GeneralSecurityException | SQLException ex) {
            previous = null;
        }
        try {
            Path snapshot = Files.createTempFile(home(), "backup-", ".db");
            byte[] sealed;
            try {
                // The password is saved before the copy is made, so the copy holds the saved password too
                SavedPassword.save(password);
                // SQLite makes the copy itself, so it is consistent even while the app is open
                AppDatabase.get().backupTo(snapshot);
                sealed = BackupCrypto.encrypt(BackupArchive.zip(snapshot), password);
            } finally {
                Files.deleteIfExists(snapshot);
            }
            // Written beside the chosen file and then moved into place, so a failed write never replaces a good backup
            Path temp = Files.createTempFile(file.toPath().toAbsolutePath().getParent(), "backup-", ".tmp");
            Files.write(temp, sealed);
            Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            show(status, "Backup saved to " + file.getAbsolutePath()
                    + ". The password is saved encrypted in MarginIQ Suite, so you can show it again with "
                    + "Show Saved Password.");
        } catch (GeneralSecurityException | IOException | SQLException ex) {
            // Put the earlier password back, so the saved one still matches the last good backup
            if (previous != null) {
                try {
                    SavedPassword.save(previous);
                } catch (GeneralSecurityException | SQLException rollbackFailed) {
                    System.err.println("Could not put back the earlier saved password: " + rollbackFailed.getMessage());
                    Notices.add("The backup could not be created, and the earlier saved password could not be put back, "
                            + "so the saved password may not match the last good backup. Check it with Show Saved Password "
                            + "before your next backup.");
                }
            }
            show(status, "Could not create the backup: " + ex.getMessage());
        } finally {
            Arrays.fill(password, '\0');
            if (previous != null) {
                Arrays.fill(previous, '\0');
            }
        }
    }

    private static void restore(Window owner, Label status) {
        Alert warn = new Alert(Alert.AlertType.CONFIRMATION);
        warn.initOwner(owner);
        warn.setTitle("Restore from backup");
        warn.setHeaderText("Replace your current data with the backup?");
        warn.setContentText("Your current data is first saved as one safety copy in your home folder, named "
                + "marginiq.db.before-restore followed by the time. The screens then show the restored data, "
                + "so there is no need to restart MarginIQ Suite.");
        Optional<ButtonType> answer = warn.showAndWait();
        if (answer.isEmpty() || answer.get() != ButtonType.OK) {
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose a MarginIQ backup");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("MarginIQ backup", "*" + EXTENSION));
        File file = chooser.showOpenDialog(owner);
        if (file == null) {
            return;
        }

        char[] password = askPassword(owner, false);
        if (password == null) {
            return;
        }
        try {
            byte[] data = Files.readAllBytes(file.toPath());
            Map<String, byte[]> files = BackupArchive.unzip(BackupCrypto.decrypt(data, password));
            writeRestored(files);
            DataRefresh.reloadAll();
            show(status, "Backup restored. Every screen now shows the restored data.");
        } catch (AEADBadTagException ex) {
            show(status, "The password is wrong, or the backup file is damaged.");
        } catch (GeneralSecurityException | IOException | SQLException ex) {
            show(status, "Could not restore the backup: " + ex.getMessage());
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    /** Shows the password saved with the most recent backup, with a button to copy it. */
    private static void showSaved(Window owner, Label status) {
        char[] saved;
        try {
            saved = SavedPassword.load();
        } catch (GeneralSecurityException ex) {
            show(status, "The saved password cannot be opened on this PC. It was saved on another computer, "
                    + "or its key has been removed.");
            return;
        } catch (SQLException ex) {
            show(status, "Could not read the saved password: " + ex.getMessage());
            return;
        }
        if (saved == null) {
            show(status, "No backup password is saved yet. A password is saved when you create a backup.");
            return;
        }
        try {
            Dialog<Void> dialog = new Dialog<>();
            dialog.initOwner(owner);
            dialog.setTitle("Saved backup password");
            dialog.setHeaderText(null);
            ButtonType copyType = new ButtonType("Copy", ButtonBar.ButtonData.LEFT);
            dialog.getDialogPane().getButtonTypes().addAll(copyType, ButtonType.CLOSE);

            TextField field = new TextField(new String(saved));
            field.setEditable(false);
            field.setPrefColumnCount(26);
            field.setMaxWidth(Double.MAX_VALUE);
            Label note = new Label("This is the password of the most recent backup. Keep it private.");
            note.setWrapText(true);
            dialog.getDialogPane().setContent(new VBox(8, note, field));
            dialog.getDialogPane().setPrefWidth(560);
            dialog.setResizable(true);

            // The copy keeps the dialog open, so the event is consumed
            Node copy = dialog.getDialogPane().lookupButton(copyType);
            copy.addEventFilter(ActionEvent.ACTION, event -> {
                ClipboardContent content = new ClipboardContent();
                content.putString(field.getText());
                Clipboard.getSystemClipboard().setContent(content);
                event.consume();
            });
            dialog.showAndWait();
        } finally {
            Arrays.fill(saved, '\0');
        }
    }

    /** Moves each current file aside as a copy, then writes the restored files in their place. */
    private static void writeRestored(Map<String, byte[]> files) throws IOException, SQLException {
        Path home = home();
        // Checked, and written to temporary files, before the current data is touched at all
        if (files.containsKey(BackupArchive.DATABASE_NAME)) {
            BackupArchive.checkDatabaseCopy(files.get(BackupArchive.DATABASE_NAME));
        }
        java.util.Map<String, Path> staged = new java.util.LinkedHashMap<>();
        try {
            for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                Path temp = Files.createTempFile(home, "restore-", ".tmp");
                Files.write(temp, entry.getValue());
                staged.put(entry.getKey(), temp);
            }
        } catch (IOException | RuntimeException ex) {
            for (Path temp : staged.values()) {
                Files.deleteIfExists(temp);
            }
            throw ex;
        }
        // The database is closed first, so its file can be moved aside and replaced
        AppDatabase.close();
        ClientStore.forget();
        // Nothing saves over the restored database until every screen has read it again (see DataRefresh)
        BusinessHistory.forgetStorage();
        PinStore.forgetStorage();
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        // The database and its journal files are moved aside together, so no journal is left to damage the restored copy.
        // Files from an older backup are moved aside too, and are brought across when the database is next opened.
        java.util.Set<String> replaced = new java.util.LinkedHashSet<>(files.keySet());
        replaced.add(BackupArchive.DATABASE_NAME);
        replaced.add(BackupArchive.DATABASE_NAME + "-wal");
        replaced.add(BackupArchive.DATABASE_NAME + "-shm");
        replaced.add(BackupArchive.DATABASE_NAME + "-journal");
        for (String name : replaced) {
            Path current = home.resolve(name);
            if (Files.exists(current)) {
                Files.move(current, home.resolve(name + ".before-restore-" + stamp),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        }
        for (Map.Entry<String, Path> entry : staged.entrySet()) {
            Files.move(entry.getValue(), home.resolve(entry.getKey()), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Asks for the password. A new backup asks for it twice, and every requirement is listed and ticked as it is
     * met. A restore only needs something typed. Either way the user can show the password while typing. Returns
     * null if the user cancels. The caller clears the array when done.
     */
    private static char[] askPassword(Window owner, boolean confirm) {
        Dialog<char[]> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(confirm ? "Choose a backup password" : "Enter the backup password");
        dialog.setHeaderText(null);

        ButtonType ok = new ButtonType("OK", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);

        // One switch shows or hides both boxes, so the password can be read while it is typed
        BooleanProperty reveal = new SimpleBooleanProperty(false);
        PasswordEntry first = new PasswordEntry(confirm ? "Enter a password" : "Password", reveal, false);
        // The confirmation box cannot be copied from or pasted into, so the password has to be typed again
        PasswordEntry second = new PasswordEntry("Type it again", reveal, true);
        CheckBox showBox = new CheckBox("Show password while typing");
        showBox.selectedProperty().bindBidirectional(reveal);

        Label problem = new Label();
        problem.getStyleClass().add("error-label");
        problem.setWrapText(true);
        problem.setVisible(false);
        problem.setManaged(false);
        problem.setMaxWidth(Double.MAX_VALUE);

        // The fields and requirements scroll when the text size is large, so nothing is cut off
        VBox body = new VBox(8);
        body.setPadding(new Insets(4));
        body.getChildren().add(new Label(confirm
                ? "Choose a password for this backup. It must meet every requirement below."
                : "Password for this backup:"));
        body.getChildren().add(first.pane);

        if (confirm) {
            VBox requirements = new VBox(3);
            List<Label> ruleLabels = new ArrayList<>();
            for (int i = 0; i < PasswordRules.rules("").size(); i++) {
                Label rule = new Label();
                rule.setWrapText(true);
                ruleLabels.add(rule);
                requirements.getChildren().add(rule);
            }
            Label strength = new Label();
            strength.getStyleClass().add("info-note");

            // Every requirement is listed and ticked as the password changes
            Runnable refresh = () -> {
                String typed = first.text();
                List<PasswordRules.Rule> rules = PasswordRules.rules(typed);
                for (int i = 0; i < rules.size(); i++) {
                    PasswordRules.Rule rule = rules.get(i);
                    Label label = ruleLabels.get(i);
                    label.setText((rule.met ? "✓  " : "✗  ") + rule.text);
                    label.setStyle("-fx-text-fill: " + (rule.met ? MET_COLOUR : TODO_COLOUR) + ";");
                }
                strength.setText(typed.isEmpty() ? "" : "Strength: " + PasswordRules.strengthLabel(typed));
            };
            first.hidden.textProperty().addListener((observed, oldText, newText) -> refresh.run());
            refresh.run();

            body.getChildren().addAll(new Label("Password requirements:"), requirements, strength);
            body.getChildren().addAll(new Label("Type the password again:"), second.pane);
        }
        body.getChildren().add(showBox);
        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(460);
        scroll.setMinHeight(240);
        // The error line stays below the scrolling part, so it is always visible
        VBox content = new VBox(8, scroll, problem);
        content.setPadding(new Insets(4));
        dialog.getDialogPane().setContent(content);
        // Wider than before, and resizable, so the dialog can be made larger if the text size needs it
        dialog.getDialogPane().setPrefWidth(620);
        dialog.getDialogPane().setMinWidth(560);
        dialog.setResizable(true);

        // The check runs before the dialog closes, so a wrong password keeps the dialog open with the reason shown
        Node okButton = dialog.getDialogPane().lookupButton(ok);
        okButton.addEventFilter(ActionEvent.ACTION, event -> {
            try {
                if (confirm) {
                    BackupCrypto.checkPassword(first.text());
                    if (!first.text().equals(second.text())) {
                        throw new InputParser.InvalidInputException("The two passwords do not match.");
                    }
                } else if (first.text().isEmpty()) {
                    throw new InputParser.InvalidInputException("Enter the backup password.");
                }
            } catch (InputParser.InvalidInputException ex) {
                UiUtil.showError(problem, ex.getMessage());
                event.consume();
            }
        });

        dialog.setResultConverter(button -> button == ok ? first.text().toCharArray() : null);
        return dialog.showAndWait().orElse(null);
    }

    /** A password box that shows its text in plain view when the reveal switch is on. */
    private static final class PasswordEntry {
        private final PasswordField hidden = new PasswordField();
        private final TextField shown = new TextField();
        final StackPane pane;

        PasswordEntry(String prompt, BooleanProperty reveal, boolean noCopyOrPaste) {
            hidden.setPromptText(prompt);
            shown.setPromptText(prompt);
            hidden.setPrefColumnCount(26);
            shown.setPrefColumnCount(26);
            hidden.setMaxWidth(Double.MAX_VALUE);
            shown.setMaxWidth(Double.MAX_VALUE);
            // Both boxes always hold the same text; only one of them is on screen at a time
            shown.textProperty().bindBidirectional(hidden.textProperty());
            hidden.visibleProperty().bind(reveal.not());
            hidden.managedProperty().bind(hidden.visibleProperty());
            shown.visibleProperty().bind(reveal);
            shown.managedProperty().bind(shown.visibleProperty());
            if (noCopyOrPaste) {
                blockCopyAndPaste(hidden);
                blockCopyAndPaste(shown);
            }
            pane = new StackPane(hidden, shown);
        }

        String text() {
            return hidden.getText();
        }
    }

    /**
     * Stops a box being copied from or pasted into: copy, cut and paste keys, the right-click menu, and dragging
     * text out of or into it. Select-all and typing still work.
     */
    private static void blockCopyAndPaste(TextField box) {
        box.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            KeyCode key = e.getCode();
            boolean copyPasteCut = e.isShortcutDown()
                    && (key == KeyCode.C || key == KeyCode.X || key == KeyCode.V || key == KeyCode.INSERT);
            boolean shiftKeys = e.isShiftDown() && (key == KeyCode.INSERT || key == KeyCode.DELETE);
            if (copyPasteCut || shiftKeys) {
                e.consume();
            }
        });
        box.addEventFilter(ContextMenuEvent.CONTEXT_MENU_REQUESTED, ContextMenuEvent::consume);
        box.addEventFilter(MouseEvent.DRAG_DETECTED, MouseEvent::consume);
        box.addEventFilter(DragEvent.ANY, DragEvent::consume);
    }

    private static Path home() {
        return Paths.get(System.getProperty("user.home"));
    }

    private static void show(Label label, String text) {
        label.setText(text);
        label.setVisible(!text.isEmpty());
        label.setManaged(!text.isEmpty());
    }
}
