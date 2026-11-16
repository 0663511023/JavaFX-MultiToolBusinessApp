package application;

import java.math.BigDecimal;
import java.util.List;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.PieChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

/**
 * Charts and tables shown under a calculator's result card. Each one is hidden until a result is on screen, and is
 * hidden again when the result is cleared, so it never shows figures for a calculation that is no longer there.
 */
final class ResultCharts {

    private ResultCharts() {
    }

    /** Shows or hides a card together with its space in the layout. */
    private static void setShown(VBox card, boolean shown) {
        card.setVisible(shown);
        card.setManaged(shown);
    }

    /** Two parts of a whole, such as cost and profit. Hidden when a part is negative, since a pie cannot show that. */
    static final class Pie {
        final VBox card;
        private final PieChart chart = new PieChart();

        Pie(String heading) {
            Label title = new Label(heading);
            title.getStyleClass().add("card-section");
            chart.setLegendVisible(true);
            chart.setLabelsVisible(false);
            chart.setAnimated(false);
            chart.setMinHeight(200);
            chart.setPrefHeight(230);
            card = new VBox(10, title, chart);
            card.getStyleClass().add("result-card");
            card.setPadding(new Insets(16));
            hide();
        }

        void show(String nameA, BigDecimal a, String nameB, BigDecimal b) {
            if (a.signum() < 0 || b.signum() < 0 || a.add(b).signum() == 0) {
                hide();
                return;
            }
            chart.setData(FXCollections.observableArrayList(
                    new PieChart.Data(nameA + ": " + Money.format("R %,.2f", a), a.doubleValue()),
                    new PieChart.Data(nameB + ": " + Money.format("R %,.2f", b), b.doubleValue())));
            setShown(card, true);
        }

        void hide() {
            chart.getData().clear();
            setShown(card, false);
        }
    }

    /** A sensitivity table (cost change, cost, profit, margin) with a bar chart of profit at each change. */
    static final class Sensitivity {
        final VBox card;
        private final GridPane table = new GridPane();
        private final BarChart<String, Number> bars;

        Sensitivity(String heading) {
            Label title = new Label(heading);
            title.getStyleClass().add("card-section");

            CategoryAxis xAxis = new CategoryAxis();
            NumberAxis yAxis = new NumberAxis();
            yAxis.setLabel("Profit per unit (R)");
            bars = new BarChart<>(xAxis, yAxis);
            bars.setLegendVisible(false);
            bars.setAnimated(false);
            bars.setMinHeight(200);
            bars.setPrefHeight(230);

            table.setHgap(18);
            table.setVgap(6);
            card = new VBox(12, title, table, bars);
            card.getStyleClass().add("result-card");
            card.setPadding(new Insets(16));
            hide();
        }

        void show(List<Scenarios.Row> rows) {
            table.getChildren().clear();
            String[] headers = {"Cost change", "Cost", "Profit per unit", "Margin"};
            for (int c = 0; c < headers.length; c++) {
                Label header = new Label(headers[c]);
                header.getStyleClass().add("metric-key");
                table.add(header, c, 0);
            }
            XYChart.Series<String, Number> series = new XYChart.Series<>();
            series.setName("Profit per unit");
            int line = 1;
            for (Scenarios.Row row : rows) {
                String change = row.changePct == 0 ? "As entered" : (row.changePct > 0 ? "+" : "") + row.changePct + "%";
                table.add(new Label(change), 0, line);
                table.add(new Label(Money.format("R %,.2f", row.cost)), 1, line);
                table.add(new Label(Money.format("R %,.2f", row.pricing.profit)), 2, line);
                table.add(new Label(Money.format("%.2f%%", row.pricing.marginPct)), 3, line);
                series.getData().add(new XYChart.Data<String, Number>(change, row.pricing.profit.doubleValue()));
                line++;
            }
            bars.getData().setAll(series);
            setShown(card, true);
        }

        void hide() {
            table.getChildren().clear();
            bars.getData().clear();
            setShown(card, false);
        }
    }
}
