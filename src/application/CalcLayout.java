package application;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Shared page layout for the calculators: a breadcrumb and title, then the inputs and the
 * results side by side. When the window is narrow, the two stack on top of each other.
 * Both panels share the width in proportion, so they grow and shrink with the window.
 */
final class CalcLayout {

    /** At or above this width the inputs and results sit side by side. Below it they stack. */
    static final double SIDE_BY_SIDE_MIN = 780;

    /** Percentage of the row given to the inputs. The results get the rest. */
    static final double INPUT_SHARE = 58;

    /** The results never get narrower than this when they sit beside the inputs. */
    static final double RESULT_MIN_WIDTH = 300;

    private CalcLayout() {}

    /** A small label to sit above a field. */
    static Label label(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("field-label");
        return label;
    }

    /** A short note shown under the title, above the inputs. */
    static Label info(String text) {
        Label note = new Label(text);
        note.getStyleClass().add("info-note");
        note.setWrapText(true);
        return note;
    }

    /** A label above its control. The control fills the width of the panel. */
    static VBox field(Label label, Node control) {
        if (control instanceof Region region) {
            region.setMaxWidth(Double.MAX_VALUE);
        }
        return new VBox(6, label, control);
    }

    /** The inputs panel: a heading, then the fields and the buttons. */
    static VBox inputsCard(Node... content) {
        Label heading = new Label("Inputs");
        heading.getStyleClass().add("panel-heading");
        VBox card = new VBox(14);
        card.getChildren().add(heading);
        card.getChildren().addAll(content);
        card.getStyleClass().add("input-card");
        card.setPadding(new Insets(18));
        return card;
    }

    /** The calculate button fills the row, with the reset button beside it. */
    static HBox buttonRow(Button calc, Button reset) {
        calc.getStyleClass().add("button-primary");
        calc.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(calc, Priority.ALWAYS);
        HBox row = new HBox(10, calc, reset);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /** The calculate and reset row, with the quote or invoice button full width under it. */
    static VBox actionRows(Button calc, Button reset, Button quote) {
        quote.getStyleClass().add("pill-button");
        quote.setMaxWidth(Double.MAX_VALUE);
        return new VBox(10, buttonRow(calc, reset), quote);
    }

    /** Clears the given fields and any error, and empties the results. */
    static void reset(Label errorLabel, ResultCard result, TextField... fields) {
        UiUtil.clearInvalid(fields);
        for (TextField field : fields) {
            field.clear();
        }
        UiUtil.showError(errorLabel, "");
        result.clear();
    }

    /** A whole calculator page. The inputs and results adjust to the space that is available. */
    static VBox page(String section, String title, Label errorLabel, VBox inputs, VBox results) {
        return page(section, title, errorLabel, null, inputs, results);
    }

    /**
     * A whole calculator page, with an optional note (such as an assumption) under the title.
     * The inputs and results adjust to the space that is available.
     */
    static VBox page(String section, String title, Label errorLabel, Label info, VBox inputs, VBox results) {
        Label crumb = new Label("Business  ›  " + section);
        crumb.getStyleClass().add("breadcrumb");
        Label heading = new Label(title);
        heading.getStyleClass().add("page-title");

        // The panels are moved into whichever row or column is showing. Each arrangement is
        // built fresh on a switch, because a panel can only sit in one container at a time.
        VBox holder = new VBox(arrange(true, inputs, results));
        holder.setMaxWidth(Double.MAX_VALUE);

        final boolean[] wide = {true};
        holder.widthProperty().addListener((obs, oldWidth, newWidth) -> {
            if (newWidth.doubleValue() <= 0) {
                return;
            }
            boolean nowWide = newWidth.doubleValue() >= SIDE_BY_SIDE_MIN;
            if (nowWide != wide[0]) {
                wide[0] = nowWide;
                holder.getChildren().setAll(arrange(nowWide, inputs, results));
            }
        });

        VBox page = new VBox(14, crumb, heading);
        if (info != null) {
            page.getChildren().add(info);
        }
        page.getChildren().addAll(errorLabel, holder);
        page.setPadding(new Insets(20));
        return page;
    }

    /** The inputs and results side by side (wide), sharing the width, or stacked (narrow). */
    private static Node arrange(boolean wide, VBox inputs, VBox results) {
        if (wide) {
            GridPane row = new GridPane();
            row.setHgap(24);
            row.setAlignment(Pos.TOP_LEFT);

            ColumnConstraints inputCol = new ColumnConstraints();
            inputCol.setPercentWidth(INPUT_SHARE);
            inputCol.setHgrow(Priority.ALWAYS);

            ColumnConstraints resultCol = new ColumnConstraints();
            resultCol.setPercentWidth(100 - INPUT_SHARE);
            resultCol.setMinWidth(RESULT_MIN_WIDTH);
            resultCol.setHgrow(Priority.ALWAYS);

            row.getColumnConstraints().addAll(inputCol, resultCol);
            row.add(inputs, 0, 0);
            row.add(results, 1, 0);
            return row;
        }
        VBox column = new VBox(20, inputs, results);
        column.setAlignment(Pos.TOP_LEFT);
        return column;
    }
}
