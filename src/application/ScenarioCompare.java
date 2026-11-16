package application;

import java.math.BigDecimal;
import java.util.Map;

import javafx.geometry.Insets;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

/**
 * Two pricing scenarios side by side. Each scenario is a cost and either a selling price or a target markup. The
 * figures come from FinanceMath through Scenarios; this class only lays them out.
 */
public class ScenarioCompare {

    static final String BY_PRICE = "Selling price (R)";
    static final String BY_MARKUP = "Target markup (%)";

    /** The inputs for one of the two scenarios. */
    private static final class Side {
        final String letter;
        final TextField nameField = new TextField();
        final TextField costField = new TextField();
        final ComboBox<String> modeCombo = new ComboBox<>();
        final Label valueLabel = CalcLayout.label("Selling price (R):");
        final TextField valueField = new TextField();

        Side(String letter, String namePrompt) {
            this.letter = letter;
            nameField.setPromptText(namePrompt);
            costField.setPromptText("e.g. 80");
            valueField.setPromptText("e.g. 120");
            modeCombo.getItems().addAll(BY_PRICE, BY_MARKUP);
            modeCombo.setValue(BY_PRICE);
            modeCombo.valueProperty().addListener((obs, oldMode, mode) -> {
                boolean markup = BY_MARKUP.equals(mode);
                valueLabel.setText(markup ? "Target markup (%):" : "Selling price (R):");
                valueField.setPromptText(markup ? "e.g. 50" : "e.g. 120");
            });
        }

        /** The name used in the table: the one typed, or the letter of the scenario. */
        String title() {
            String typed = nameField.getText() == null ? "" : nameField.getText().trim();
            return typed.isEmpty() ? "Scenario " + letter : typed;
        }

        /** Reads this scenario's figures. A wrong entry marks its field red. */
        FinanceMath.Pricing read() throws InputParser.InvalidInputException {
            BigDecimal cost = UiUtil.money(costField, title() + " cost price", 0);
            boolean markup = BY_MARKUP.equals(modeCombo.getValue());
            BigDecimal input = UiUtil.money(valueField, title() + (markup ? " target markup" : " selling price"), 0);
            return Scenarios.scenario(cost, markup, input);
        }

        /** Fills this scenario from figures sent by another tool. Keys start with the letter, such as "A.cost". */
        void load(Map<String, String> values, String prefix) {
            nameField.setText(values.getOrDefault(prefix + "name", ""));
            costField.setText(values.getOrDefault(prefix + "cost", ""));
            modeCombo.setValue(Boolean.parseBoolean(values.getOrDefault(prefix + "byMarkup", "false"))
                    ? BY_MARKUP : BY_PRICE);
            valueField.setText(values.getOrDefault(prefix + "value", ""));
            UiUtil.clearInvalid(nameField, costField, valueField);
        }

        void clear() {
            UiUtil.clearInvalid(nameField, costField, valueField);
            nameField.clear();
            costField.clear();
            valueField.clear();
            modeCombo.setValue(BY_PRICE);
        }
    }

    public static VBox createLayout(boolean darkTheme) {
        Label errorLabel = UiUtil.createErrorLabel();
        Side a = new Side("A", "e.g. Current price");
        Side b = new Side("B", "e.g. New price");

        // The table, chart and note describe the figures on screen, so they are cleared when anything changes
        GridPane grid = new GridPane();
        grid.setHgap(18);
        grid.setVgap(6);
        CategoryAxis xAxis = new CategoryAxis();
        NumberAxis yAxis = new NumberAxis();
        yAxis.setLabel("Profit per unit (R)");
        BarChart<String, Number> chart = new BarChart<>(xAxis, yAxis);
        chart.setLegendVisible(false);
        chart.setAnimated(false);
        chart.setMinHeight(200);
        chart.setPrefHeight(230);
        Label note = new Label();
        note.getStyleClass().add("output-label");
        note.setWrapText(true);

        Label heading = new Label("COMPARISON");
        heading.getStyleClass().add("card-section");
        VBox results = new VBox(12, heading, grid, chart, note);
        results.getStyleClass().add("result-card");
        results.setPadding(new Insets(18));

        Runnable hideResults = () -> {
            grid.getChildren().clear();
            chart.getData().clear();
            note.setText("");
            note.getStyleClass().setAll("output-label");
            results.setVisible(false);
            results.setManaged(false);
        };
        hideResults.run();

        a.modeCombo.valueProperty().addListener((obs, oldMode, mode) -> hideResults.run());
        b.modeCombo.valueProperty().addListener((obs, oldMode, mode) -> hideResults.run());

        Button calcBtn = new Button("Compare");
        Button resetBtn = new Button("Reset");

        calcBtn.setOnAction(e -> {
            hideResults.run();
            try {
                UiUtil.clearInvalid(a.costField, a.valueField, b.costField, b.valueField);
                FinanceMath.Pricing pa = a.read();
                FinanceMath.Pricing pb = b.read();
                UiUtil.showError(errorLabel, "");
                String nameA = a.title();
                String nameB = b.title();
                fill(grid, chart, note, results, nameA, nameB, pa, pb);
            } catch (InputParser.InvalidInputException ex) {
                UiUtil.showError(errorLabel, "Error: " + ex.getMessage());
            }
        });
        resetBtn.setOnAction(e -> {
            hideResults.run();
            a.clear();
            b.clear();
            UiUtil.showError(errorLabel, "");
        });

        Label headA = new Label("SCENARIO A");
        headA.getStyleClass().add("card-section");
        Label headB = new Label("SCENARIO B");
        headB.getStyleClass().add("card-section");
        VBox inputs = CalcLayout.inputsCard(
                headA,
                CalcLayout.field(CalcLayout.label("Name (optional):"), a.nameField),
                CalcLayout.field(CalcLayout.label("Cost Price (R):"), a.costField),
                CalcLayout.field(CalcLayout.label("Calculation:"), a.modeCombo),
                CalcLayout.field(a.valueLabel, a.valueField),
                headB,
                CalcLayout.field(CalcLayout.label("Name (optional):"), b.nameField),
                CalcLayout.field(CalcLayout.label("Cost Price (R):"), b.costField),
                CalcLayout.field(CalcLayout.label("Calculation:"), b.modeCombo),
                CalcLayout.field(b.valueLabel, b.valueField),
                CalcLayout.buttonRow(calcBtn, resetBtn));

        // Sent from Margin & Markup and other tools: the scenarios are filled in, and the user presses Compare
        CalcLinks.receiver("Scenario Comparison", values -> {
            if (values.containsKey("A.cost")) {
                a.load(values, "A.");
            }
            if (values.containsKey("B.cost")) {
                b.load(values, "B.");
            }
            UiUtil.showError(errorLabel, "");
            hideResults.run();
        });

        return CalcLayout.page("Pricing & Fees", "Scenario Comparison", errorLabel,
                CalcLayout.info("Compare two prices, or a price and a markup, for the same cost. "
                        + "The difference is B minus A."),
                inputs, results);
    }

    /** Fills the table, chart and note from the two scenarios, then shows the card. */
    private static void fill(GridPane grid, BarChart<String, Number> chart, Label note, VBox results,
                             String nameA, String nameB, FinanceMath.Pricing pa, FinanceMath.Pricing pb) {
        grid.getChildren().clear();
        String[] headers = {"Measure", nameA, nameB, "Difference (B − A)"};
        for (int c = 0; c < headers.length; c++) {
            Label header = new Label(headers[c]);
            header.getStyleClass().add("metric-key");
            grid.add(header, c, 0);
        }

        BigDecimal priceDiff = pb.sellingPrice.subtract(pa.sellingPrice);
        addRow(grid, 1, "Selling price",
                money(pa.sellingPrice), money(pb.sellingPrice), signedMoney(priceDiff));
        addRow(grid, 2, "Profit per unit",
                money(pa.profit), money(pb.profit), signedMoney(Scenarios.profitDifference(pa, pb)));
        addRow(grid, 3, "Margin",
                percent(pa.marginPct), percent(pb.marginPct), signedPercent(Scenarios.marginDifference(pa, pb)));
        String markupDiff = pa.markupPct == null || pb.markupPct == null
                ? "n/a" : signedPercent(pb.markupPct.subtract(pa.markupPct));
        addRow(grid, 4, "Markup", percent(pa.markupPct), percent(pb.markupPct), markupDiff);

        // Two bars with the same name would merge into one, so the second gets a suffix
        String chartB = nameB.equals(nameA) ? nameB + " (B)" : nameB;
        XYChart.Series<String, Number> series = new XYChart.Series<>();
        series.setName("Profit per unit");
        series.getData().add(new XYChart.Data<String, Number>(nameA, pa.profit.doubleValue()));
        series.getData().add(new XYChart.Data<String, Number>(chartB, pb.profit.doubleValue()));
        chart.getData().setAll(series);

        boolean loss = pa.profit.signum() < 0 || pb.profit.signum() < 0;
        note.setText(loss ? "One of these scenarios sells below cost, so it loses money on every unit." : "");
        note.getStyleClass().setAll(loss ? "advice-high" : "output-label");

        results.setVisible(true);
        results.setManaged(true);
    }

    private static void addRow(GridPane grid, int line, String measure, String first, String second, String diff) {
        grid.add(new Label(measure), 0, line);
        grid.add(new Label(first), 1, line);
        grid.add(new Label(second), 2, line);
        grid.add(new Label(diff), 3, line);
    }

    private static String money(BigDecimal value) {
        return Money.format("R %,.2f", value);
    }

    private static String signedMoney(BigDecimal diff) {
        return (diff.signum() > 0 ? "+" : "") + Money.format("R %,.2f", diff);
    }

    private static String percent(BigDecimal value) {
        return value == null ? "n/a (cost is zero)" : Money.format("%.2f%%", value);
    }

    private static String signedPercent(BigDecimal diff) {
        return (diff.signum() > 0 ? "+" : "") + Money.format("%.2f%%", diff);
    }
}
