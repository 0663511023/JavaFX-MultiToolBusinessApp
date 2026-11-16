package application;

import java.util.ArrayList;
import java.util.List;

import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;
import javafx.util.Duration;

/**
 * The results panel: a highlighted headline figure, then labelled rows, then any notes.
 * A Copy button puts the whole result on the clipboard as plain text.
 */
final class ResultCard {

    private static final String EMPTY = "—";

    final VBox root = new VBox(12);

    private final Label caption = new Label();
    private final Label headline = new Label(EMPTY);
    private final VBox rows = new VBox(10);
    private final VBox notes = new VBox(6);
    private final Button copyButton = new Button("Copy");
    private final List<Label> keys = new ArrayList<>();
    private final List<Label> values = new ArrayList<>();
    private final List<Label> dynamicNotes = new ArrayList<>();

    ResultCard(String headlineCaption) {
        caption.setText(headlineCaption);
        caption.getStyleClass().add("hero-caption");
        caption.setWrapText(true);
        caption.setTextAlignment(TextAlignment.CENTER);
        headline.getStyleClass().add("hero-value");
        headline.setWrapText(true);
        headline.setTextAlignment(TextAlignment.CENTER);

        VBox hero = new VBox(4, caption, headline);
        hero.getStyleClass().add("hero-box");
        hero.setAlignment(Pos.CENTER);
        hero.setMaxWidth(Double.MAX_VALUE);

        Label heading = new Label("Result");
        heading.getStyleClass().add("panel-heading");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        copyButton.getStyleClass().add("copy-button");
        copyButton.setDisable(true);
        copyButton.setOnAction(e -> copyToClipboard());
        HBox header = new HBox(8, heading, spacer, copyButton);
        header.setAlignment(Pos.CENTER_LEFT);

        root.getStyleClass().add("result-card");
        root.setPadding(new Insets(18));
        root.setAlignment(Pos.TOP_LEFT);
        root.setMaxWidth(Double.MAX_VALUE);
        root.getChildren().addAll(header, hero, rows, notes);
    }

    void setCaption(String text) {
        caption.setText(text);
    }

    void setHeadline(String text) {
        headline.setText(text);
        copyButton.setDisable(EMPTY.equals(text));
    }

    /**
     * A row with its name on the left and its value on the right. The name wraps onto more
     * lines if it must; the value keeps its full width so a figure is never cut short.
     * Returns the value to update.
     */
    Label addRow(String name) {
        Label key = new Label(name);
        key.getStyleClass().add("metric-key");
        key.setWrapText(true);
        key.setMinWidth(Region.USE_PREF_SIZE);
        HBox.setHgrow(key, Priority.ALWAYS);
        key.setMaxWidth(Double.MAX_VALUE);

        Label value = new Label(EMPTY);
        value.getStyleClass().add("metric-value");
        value.setWrapText(true);
        value.setTextAlignment(TextAlignment.RIGHT);
        value.setMinWidth(Region.USE_PREF_SIZE);

        HBox row = new HBox(12, key, value);
        row.setAlignment(Pos.CENTER_LEFT);
        rows.getChildren().add(row);
        keys.add(key);
        values.add(value);
        return value;
    }

    /** Changes the name of a row, found by the value label it returns. Used when the meaning of a row changes. */
    void renameRow(Label value, String name) {
        int index = values.indexOf(value);
        if (index >= 0) {
            keys.get(index).setText(name);
        }
    }

    /** A note under the rows that is cleared with the results. Returns the label to update. */
    Label addNote() {
        Label note = new Label();
        note.getStyleClass().add("output-label");
        note.setWrapText(true);
        notes.getChildren().add(note);
        dynamicNotes.add(note);
        return note;
    }

    /** A note that stays on screen, such as a fixed assumption. */
    void addStaticNote(String text) {
        Label note = new Label(text);
        note.getStyleClass().add("output-label");
        note.setWrapText(true);
        notes.getChildren().add(note);
    }

    /** Puts every figure and note back to its empty state. */
    void clear() {
        headline.setText(EMPTY);
        copyButton.setDisable(true);
        for (Label value : values) {
            value.setText(EMPTY);
        }
        for (Label note : dynamicNotes) {
            note.setText("");
        }
    }

    /** The result as plain text: the caption and headline, each row, then any notes with text. */
    String plainText() {
        StringBuilder text = new StringBuilder();
        text.append(caption.getText()).append('\n');
        text.append(headline.getText()).append('\n');
        for (Node node : rows.getChildren()) {
            if (node instanceof HBox row
                    && row.getChildren().size() == 2
                    && row.getChildren().get(0) instanceof Label key
                    && row.getChildren().get(1) instanceof Label value) {
                text.append(key.getText()).append(": ").append(value.getText()).append('\n');
            }
        }
        for (Node node : notes.getChildren()) {
            if (node instanceof Label note && !note.getText().isBlank()) {
                text.append(note.getText()).append('\n');
            }
        }
        return text.toString().trim();
    }

    /** Copies the result to the clipboard. The button confirms for a moment, or says why it failed. */
    private void copyToClipboard() {
        String message;
        try {
            ClipboardContent content = new ClipboardContent();
            content.putString(plainText());
            Clipboard.getSystemClipboard().setContent(content);
            message = "Copied";
        } catch (RuntimeException ex) {
            message = "Copy failed";
        }
        copyButton.setText(message);
        PauseTransition back = new PauseTransition(Duration.seconds(1.5));
        back.setOnFinished(e -> copyButton.setText("Copy"));
        back.play();
    }
}
