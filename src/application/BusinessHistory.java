package application;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.ObservableSet;
import javafx.collections.SetChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.DatePicker;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TextField;
import javafx.scene.Node;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

/**
 * Read-only calculation log ("tape"). Entries can be deleted or cleared, never edited.
 * Stored in the MarginIQ database, so it is kept with the clients and the quotations and invoices.
 * Shown grouped by day.
 *
 * Selection works like Windows Explorer:
 *  - Tick an entry's checkbox to add or remove just that entry.
 *  - Click an entry to select only it (its checkbox is ticked, others are cleared).
 *  - Ctrl-click adds or removes one entry; Shift-click selects a range.
 *  - Arrow keys move and select; Shift+arrows select a range; Ctrl+arrows move without
 *    changing the selection; Space ticks the current entry; Ctrl+A selects all.
 */
public class BusinessHistory {

    /** A day heading in the list. Shown only, never stored. */
    static final class DayHeader {
        final String label;

        DayHeader(String label) {
            this.label = label;
        }
    }

    /** Shown in the filter box when every calculator is listed. */
    private static final String ALL_CALCULATIONS = "All calculations";
    /** Below this width the action card sits under the list instead of beside it. */
    private static final double SIDE_BY_SIDE_MIN = 700;
    /** The filter the ledger applies. The filter bar sets it. */
    private static HistoryFilter filter = HistoryFilter.ALL;

    private static final ObservableList<String> historyList = FXCollections.observableArrayList();
    /** What the list shows: a DayHeader, then the HistoryRecords for that day. */
    private static final ObservableList<Object> displayList = FXCollections.observableArrayList();
    /** True once the history has been read from the database. Until then nothing is written, so it is not overwritten. */
    private static volatile boolean storageReady;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Writes the history file on one background thread, so a large log never freezes the screen. Saves run in
     * the order they were asked for, and each writes the list as it was when it was asked for. The thread
     * ends once the queue is empty, so the app still closes cleanly after its last save.
     */
    private static final ThreadPoolExecutor SAVER = createSaver();

    static {
        historyList.addListener((ListChangeListener<String>) change -> rebuildDisplay());
        AppSettings.addChangeListener(BusinessHistory::rebuildDisplay);
        loadHistory();
        applyRetention();
    }

    /**
     * Moves entries older than the retention period from Settings into the archive in the database. The archive is
     * written first, so if that fails nothing is removed.
     */
    private static void applyRetention() {
        int days = AppSettings.retentionDays();
        // The retention period is applied here, so it waits for a Refresh until this runs again
        PendingChanges.applied("retention", PendingChanges.Action.REFRESH, "Keep calculation history",
                () -> String.valueOf(AppSettings.retentionDays()));
        if (days <= 0 || !storageReady) {
            return;
        }
        LocalDate cutoff = LocalDate.now().minusDays(days);
        List<String> expired = new ArrayList<>();
        for (String raw : historyList) {
            HistoryRecord record = HistoryRecord.parse(raw);
            if (record != null && record.date != null && record.date.isBefore(cutoff)) {
                expired.add(raw);
            }
        }
        if (expired.isEmpty()) {
            return;
        }
        try {
            AppDatabase.get().archiveHistory(expired);
        } catch (SQLException | RuntimeException ex) {
            Notices.addOnce("archive", "Old calculation history could not be moved to the archive, so it was kept on "
                    + "the list. (" + ex.getMessage() + ")");
            return;
        }
        historyList.removeAll(expired);
        saveHistoryToFile();
    }

    private BusinessHistory() {}

    public static void addEntry(String calculatorType, String expression, String result) {
        String timestamp = LocalDateTime.now().format(TIME_FORMATTER);
        String entry = String.format("[%s] (%s)  %s -> %s", calculatorType, timestamp, expression, result);
        historyList.add(0, entry); // newest first
        saveHistoryToFile();
    }

    public static void clearHistory() {
        historyList.clear();
        saveHistoryToFile();
    }

    /** Removes the given entries in one save. Duplicate entries are matched once each. */
    public static void removeEntries(List<HistoryRecord> records) {
        TreeSet<Integer> indices = new TreeSet<>(Comparator.reverseOrder());
        for (HistoryRecord record : records) {
            // Each record takes its own line, so two identical lines are both removed
            for (int i = 0; i < historyList.size(); i++) {
                if (!indices.contains(i) && historyList.get(i).equals(record.raw)) {
                    indices.add(i);
                    break;
                }
            }
        }
        for (int index : indices) {
            historyList.remove(index);
        }
        saveHistoryToFile();
    }

    public static ObservableList<String> getHistory() {
        return historyList;
    }

    /**
     * Rebuilds the grouped list: the entries that pass the filter, in the chosen order, with a day
     * heading wherever the date changes.
     */
    private static void rebuildDisplay() {
        displayList.clear();
        LocalDate currentDay = null;
        boolean first = true;
        int count = historyList.size();
        for (int i = 0; i < count; i++) {
            // The stored order is newest first, so oldest first reads it backwards
            String raw = filter.oldestFirst ? historyList.get(count - 1 - i) : historyList.get(i);
            HistoryRecord record = HistoryRecord.parse(raw);
            if (!filter.matches(record)) {
                continue;
            }
            if (first || !Objects.equals(record.date, currentDay)) {
                displayList.add(new DayHeader(record.dayLabel()));
                currentDay = record.date;
                first = false;
            }
            displayList.add(record);
        }
    }

    private static List<HistoryRecord> allRecords() {
        List<HistoryRecord> records = new ArrayList<>();
        for (String raw : historyList) {
            records.add(HistoryRecord.parse(raw));
        }
        return records;
    }

    /** The ticked entries, in the order they appear in the list. Day headings are skipped. */
    private static List<HistoryRecord> selectedRecords(ObservableSet<String> selected) {
        List<HistoryRecord> out = new ArrayList<>();
        for (Object item : displayList) {
            if (item instanceof HistoryRecord record && selected.contains(record.raw)) {
                out.add(record);
            }
        }
        return out;
    }

    /** Entries in display order (no day headings), for keyboard moves and Shift-click ranges. */
    private static List<String> entryOrder() {
        List<String> order = new ArrayList<>();
        for (Object item : displayList) {
            if (item instanceof HistoryRecord record) {
                order.add(record.raw);
            }
        }
        return order;
    }

    /** Index in the visible list of the entry with this text, or -1. */
    private static int displayIndexOf(String raw) {
        for (int i = 0; i < displayList.size(); i++) {
            if (displayList.get(i) instanceof HistoryRecord record && record.raw.equals(raw)) {
                return i;
            }
        }
        return -1;
    }

    private static void toggle(ObservableSet<String> selected, String raw) {
        if (selected.contains(raw)) {
            selected.remove(raw);
        } else {
            selected.add(raw);
        }
    }

    /** Replaces the selection with every entry from one end of the range to the other. */
    private static void selectRange(ObservableSet<String> selected, List<String> order, String from, String to) {
        int a = order.indexOf(from);
        int b = order.indexOf(to);
        if (a < 0 || b < 0) {
            return;
        }
        selected.clear();
        for (int i = Math.min(a, b); i <= Math.max(a, b); i++) {
            selected.add(order.get(i));
        }
    }

    public static StackPane createHistoryView(boolean darkTheme, Function<HistoryRecord, String> onSend) {
        // The same breadcrumb and title as the calculator pages
        Label crumb = new Label("Business  ›  Records");
        crumb.getStyleClass().add("breadcrumb");
        Label heading = new Label("Calculation Ledger & Tape History");
        heading.getStyleClass().add("page-title");

        Label statusLabel = new Label("");
        statusLabel.getStyleClass().add("error-label");
        statusLabel.setWrapText(true);
        statusLabel.setVisible(false);
        statusLabel.setManaged(false);

        Label emptyLabel = new Label("No calculations yet. Results you calculate appear here.");
        emptyLabel.getStyleClass().add("output-label");
        emptyLabel.setWrapText(true);

        // Our own selection, separate from the list's built-in highlighting,
        // so ticking one entry never clears the others.
        ObservableSet<String> selected = FXCollections.observableSet(new LinkedHashSet<>());
        String[] anchor = new String[1]; // where Shift-click and Shift+arrow ranges start
        String[] cursor = new String[1]; // the entry the keyboard is on

        ListView<Object> listView = new ListView<>(displayList);
        listView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        listView.setPlaceholder(emptyLabel);
        listView.setMinHeight(240);
        listView.setPrefHeight(420);
        listView.getStyleClass().add("list-view");

        Label hintLabel = new Label("Tick boxes to add entries, or click one to select only it. "
                + "Double-click an entry to send it to its calculator. "
                + "Ctrl-click adds or removes one entry, Shift-click selects a range. "
                + "Arrow keys move, Space ticks, Ctrl+A selects all.");
        hintLabel.getStyleClass().add("output-label");
        hintLabel.setWrapText(true);

        // Sends one saved calculation to its calculator, or explains why it cannot
        Consumer<HistoryRecord> sendRecord = record -> {
            String problem = onSend == null ? "Sending to a calculator is not available here." : onSend.apply(record);
            if (problem == null) {
                showStatus(statusLabel, "Opened in its calculator with the inputs filled in. "
                        + "Press Calculate to run it again.", true);
            } else {
                showStatus(statusLabel, problem, false);
            }
        };

        // ---- Filter bar: search, period, dates, times and sort
        Label filterHeading = sectionLabel("FILTER", "card-section");
        Label resultCount = new Label("");
        resultCount.getStyleClass().add("home-stats");
        Region filterSpacer = new Region();
        HBox.setHgrow(filterSpacer, Priority.ALWAYS);
        HBox filterTop = new HBox(10, filterHeading, filterSpacer, resultCount);
        filterTop.setAlignment(Pos.CENTER_LEFT);

        TextField searchField = new TextField();
        searchField.setPromptText("Search expressions, results or tools…");
        searchField.setMaxWidth(Double.MAX_VALUE);

        ComboBox<String> periodCombo = new ComboBox<>();
        periodCombo.getItems().addAll(HistoryFilter.PERIOD_ALL, HistoryFilter.PERIOD_TODAY,
                HistoryFilter.PERIOD_LAST_7, HistoryFilter.PERIOD_LAST_30, HistoryFilter.PERIOD_THIS_MONTH,
                HistoryFilter.PERIOD_CUSTOM);
        periodCombo.setValue(HistoryFilter.PERIOD_ALL);
        periodCombo.setMaxWidth(Double.MAX_VALUE);

        DatePicker fromPicker = new DatePicker();
        fromPicker.setPromptText("From");
        fromPicker.setMaxWidth(Double.MAX_VALUE);
        DatePicker toPicker = new DatePicker();
        toPicker.setPromptText("To");
        toPicker.setMaxWidth(Double.MAX_VALUE);

        TextField timeFromField = new TextField();
        timeFromField.setPromptText("e.g. 08:00");
        timeFromField.setMaxWidth(Double.MAX_VALUE);
        TextField timeToField = new TextField();
        timeToField.setPromptText("e.g. 17:30");
        timeToField.setMaxWidth(Double.MAX_VALUE);

        ComboBox<String> sortCombo = new ComboBox<>();
        sortCombo.getItems().addAll(HistoryFilter.SORT_NEWEST, HistoryFilter.SORT_OLDEST);
        sortCombo.setValue(HistoryFilter.SORT_NEWEST);
        sortCombo.setMaxWidth(Double.MAX_VALUE);

        GridPane filterGrid = new GridPane();
        filterGrid.setHgap(12);
        filterGrid.setVgap(10);
        for (int i = 0; i < 3; i++) {
            ColumnConstraints column = new ColumnConstraints();
            column.setPercentWidth(100.0 / 3);
            column.setHgrow(Priority.ALWAYS);
            filterGrid.getColumnConstraints().add(column);
        }
        filterGrid.addRow(0,
                filterCell("PERIOD", periodCombo), filterCell("FROM", fromPicker), filterCell("TO", toPicker));
        filterGrid.addRow(1,
                filterCell("TIME FROM", timeFromField), filterCell("TIME TO", timeToField),
                filterCell("SORT", sortCombo));

        Label filterNote = new Label("");
        filterNote.getStyleClass().add("error-label");
        filterNote.setWrapText(true);
        filterNote.setVisible(false);
        filterNote.setManaged(false);
        Region footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);
        Button clearFiltersBtn = new Button("Clear filters");
        clearFiltersBtn.getStyleClass().add("pill-button");
        HBox filterFooter = new HBox(10, filterNote, footerSpacer, clearFiltersBtn);
        filterFooter.setAlignment(Pos.CENTER_LEFT);

        VBox filterBar = new VBox(12, filterTop, searchField, filterGrid, filterFooter);
        filterBar.getStyleClass().add("result-card");
        filterBar.setPadding(new Insets(16));

        // ---- Left: the filter bar, then the ledger. The list takes all the space it can get.
        VBox ledger = new VBox(12, filterBar, listView, hintLabel);
        VBox.setVgrow(listView, Priority.ALWAYS);
        VBox.setVgrow(ledger, Priority.ALWAYS);
        HBox.setHgrow(ledger, Priority.ALWAYS);

        // ---- Right: one action card, with the summary, show, open, delete and export in groups
        Label selectionHeading = sectionLabel("SELECTION", "card-section");

        Label summaryCaption = new Label("ENTRIES SELECTED");
        summaryCaption.getStyleClass().add("hero-caption");
        Label summaryValue = new Label("0 of 0");
        summaryValue.getStyleClass().add("hero-value");
        VBox summary = new VBox(4, summaryCaption, summaryValue);
        summary.getStyleClass().add("hero-box");
        summary.setAlignment(Pos.CENTER);
        summary.setMaxWidth(Double.MAX_VALUE);

        Button selectAllBtn = new Button("Select All");
        selectAllBtn.setOnAction(e -> {
            selected.clear();
            selected.addAll(entryOrder());
        });
        Button clearSelectionBtn = new Button("Clear Selection");
        clearSelectionBtn.setOnAction(e -> selected.clear());
        VBox selectButtons = new VBox(8, selectAllBtn, clearSelectionBtn);
        fillWidth(selectAllBtn, clearSelectionBtn);

        Label showHeading = sectionLabel("SHOW", "card-section");
        ComboBox<String> showCombo = new ComboBox<>();
        showCombo.setMaxWidth(Double.MAX_VALUE);

        Label openHeading = sectionLabel("OPEN", "card-section");
        Button sendBtn = new Button("Send to Calculator");
        sendBtn.getStyleClass().add("button-primary");
        sendBtn.setMaxWidth(Double.MAX_VALUE);
        sendBtn.setDisable(true);
        sendBtn.setOnAction(e -> {
            List<HistoryRecord> chosen = selectedRecords(selected);
            if (chosen.size() == 1) {
                sendRecord.accept(chosen.get(0));
            }
        });
        Label sendHint = new Label("Select one entry to open its calculator with the same inputs filled in.");
        sendHint.getStyleClass().add("output-label");
        sendHint.setWrapText(true);

        // Pins every selected calculation to the Home page. Ones already pinned are skipped.
        Button pinBtn = new Button("Pin Selected to Home");
        pinBtn.setMaxWidth(Double.MAX_VALUE);
        pinBtn.setDisable(true);
        pinBtn.setOnAction(e -> {
            int added = 0;
            int already = 0;
            String problem = null;
            for (HistoryRecord record : selectedRecords(selected)) {
                if (PinStore.isPinned(record.raw)) {
                    already++;
                    continue;
                }
                try {
                    PinStore.add(record.raw);
                    added++;
                } catch (InputParser.InvalidInputException ex) {
                    problem = ex.getMessage(); // the limit was reached
                    break;
                } catch (IOException ex) {
                    problem = "Could not pin to Home: " + ex.getMessage();
                    break;
                }
            }
            // Stays enabled while some selected entries are still not pinned
            pinBtn.setDisable(selectedRecords(selected).stream().allMatch(r -> PinStore.isPinned(r.raw)));
            if (problem != null) {
                showStatus(statusLabel, (added > 0 ? "Pinned " + added + ". " : "") + problem, false);
            } else if (added == 0) {
                showStatus(statusLabel, "Those calculations are already pinned to Home.", false);
            } else {
                showStatus(statusLabel, "Pinned " + added + (added == 1 ? " calculation" : " calculations")
                        + " to Home." + (already > 0 ? " " + already + " were already pinned." : ""), true);
            }
        });

        Label deleteHeading = sectionLabel("DELETE", "card-section");
        Button deleteBtn = new Button("Delete Selected");
        deleteBtn.getStyleClass().add("button-danger");
        deleteBtn.setMaxWidth(Double.MAX_VALUE);
        deleteBtn.setOnAction(e -> {
            List<HistoryRecord> chosen = selectedRecords(selected);
            if (chosen.isEmpty()) {
                showStatus(statusLabel, "Tick one or more calculation entries first.", false);
            } else if (!canChange()) {
                showStatus(statusLabel, CANNOT_CHANGE, false);
            } else {
                removeEntries(chosen);
                selected.clear();
                showStatus(statusLabel, "", true);
            }
        });

        Button clearBtn = new Button("Clear All History");
        clearBtn.getStyleClass().add("button-danger");
        clearBtn.setMaxWidth(Double.MAX_VALUE);
        clearBtn.setOnAction(e -> {
            if (!canChange()) {
                showStatus(statusLabel, CANNOT_CHANGE, false);
                return;
            }
            clearHistory();
            selected.clear();
            showStatus(statusLabel, "", true);
        });

        Label exportHeading = sectionLabel("EXPORT", "card-section");
        ComboBox<String> formatCombo = new ComboBox<>();
        formatCombo.getItems().addAll("PDF document (.pdf)", "Excel workbook (.xlsx)", "CSV file (.csv)");
        formatCombo.setValue(formatCombo.getItems().get(0));
        formatCombo.setMaxWidth(Double.MAX_VALUE);

        Button exportSelectedBtn = new Button("Export Selected");
        exportSelectedBtn.getStyleClass().add("button-primary");
        exportSelectedBtn.setMaxWidth(Double.MAX_VALUE);
        exportSelectedBtn.setDisable(true);
        exportSelectedBtn.setOnAction(e -> exportHistory(chosenFormat(formatCombo),
                listView.getScene().getWindow(), statusLabel, selectedRecords(selected), "selected"));

        Button exportAllBtn = new Button("Export All");
        exportAllBtn.getStyleClass().add("button-primary");
        exportAllBtn.setMaxWidth(Double.MAX_VALUE);
        exportAllBtn.setOnAction(e -> exportHistory(chosenFormat(formatCombo),
                listView.getScene().getWindow(), statusLabel, allRecords(), "all"));

        VBox card = new VBox(10, selectionHeading, summary, selectButtons,
                showHeading, showCombo,
                openHeading, sendBtn, sendHint, pinBtn,
                deleteHeading, deleteBtn, clearBtn,
                exportHeading, formatCombo, exportSelectedBtn, exportAllBtn);
        card.getStyleClass().add("result-card");
        card.setPadding(new Insets(18));
        card.setMinWidth(240);
        VBox.setMargin(showHeading, new Insets(10, 0, 0, 0));
        VBox.setMargin(openHeading, new Insets(10, 0, 0, 0));
        VBox.setMargin(deleteHeading, new Insets(10, 0, 0, 0));
        VBox.setMargin(exportHeading, new Insets(10, 0, 0, 0));

        listView.setCellFactory(lv -> new ListCell<Object>() {
            private final CheckBox checkBox = new CheckBox();
            private boolean hovered;

            {
                checkBox.setOnAction(e -> {
                    HistoryRecord record = currentRecord();
                    if (record != null) {
                        // The checkbox has already ticked or unticked itself by now
                        if (checkBox.isSelected()) {
                            selected.add(record.raw);
                        } else {
                            selected.remove(record.raw);
                        }
                        anchor[0] = record.raw;
                        cursor[0] = record.raw;
                    }
                });
                // The checkbox handles its own clicks. The row ignores them (see fromCheckBox).

                setOnMouseEntered(e -> {
                    hovered = true;
                    updateCheckBoxVisibility();
                });
                setOnMouseExited(e -> {
                    hovered = false;
                    updateCheckBoxVisibility();
                });
                setOnMouseClicked(e -> handleRowClick(e));
            }

            private HistoryRecord currentRecord() {
                Object item = getItem();
                return item instanceof HistoryRecord record ? record : null;
            }

            private void handleRowClick(MouseEvent e) {
                HistoryRecord record = currentRecord();
                if (record == null || e.getButton() != MouseButton.PRIMARY || fromCheckBox(e.getTarget())) {
                    return;
                }
                if (e.isShiftDown() && anchor[0] != null) {
                    selectRange(selected, entryOrder(), anchor[0], record.raw);
                } else if (e.isControlDown()) {
                    toggle(selected, record.raw);
                    anchor[0] = record.raw;
                } else {
                    // A plain click selects only this entry and clears the rest
                    selected.clear();
                    selected.add(record.raw);
                    anchor[0] = record.raw;
                }
                cursor[0] = record.raw;
                lv.getFocusModel().focus(getIndex());
                if (e.getClickCount() == 2) {
                    sendRecord.accept(record);
                }
            }

            /** True when a click landed on this row's checkbox, which handles its own clicks. */
            private boolean fromCheckBox(Object target) {
                if (!(target instanceof Node)) {
                    return false;
                }
                Node node = (Node) target;
                while (node != null && node != this) {
                    if (node == checkBox) {
                        return true;
                    }
                    node = node.getParent();
                }
                return false;
            }

            private void updateCheckBoxVisibility() {
                HistoryRecord record = currentRecord();
                boolean ticked = record != null && selected.contains(record.raw);
                // Hidden checkboxes keep their space, so the text does not jump
                checkBox.setOpacity(hovered || ticked ? 1 : 0);
            }

            @Override
            protected void updateItem(Object item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("day-header-cell", "checked-row");
                setMouseTransparent(false);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else if (item instanceof DayHeader header) {
                    setText(header.label);
                    setGraphic(null);
                    getStyleClass().add("day-header-cell");
                    setMouseTransparent(true); // headings cannot be selected
                } else if (item instanceof HistoryRecord record) {
                    hovered = isHover(); // a row can be reused while scrolling, so re-check the mouse position
                    boolean ticked = selected.contains(record.raw);
                    setText(record.displayLine());
                    checkBox.setSelected(ticked);
                    setGraphic(checkBox);
                    if (ticked) {
                        getStyleClass().add("checked-row");
                    }
                    updateCheckBoxVisibility();
                }
            }
        });

        // Keyboard selection, like Explorer. Handled before the list's own key handling.
        listView.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            List<String> order = entryOrder();
            if (order.isEmpty()) {
                return;
            }
            int position = cursor[0] == null ? -1 : order.indexOf(cursor[0]);
            KeyCode key = e.getCode();

            if (key == KeyCode.UP || key == KeyCode.DOWN) {
                int step = key == KeyCode.DOWN ? 1 : -1;
                int next;
                if (position < 0) {
                    next = step > 0 ? 0 : order.size() - 1;
                } else {
                    next = Math.max(0, Math.min(order.size() - 1, position + step));
                }
                String raw = order.get(next);
                if (e.isShiftDown()) {
                    selectRange(selected, order, anchor[0] == null ? raw : anchor[0], raw);
                } else if (!e.isControlDown()) {
                    // Plain arrow moves the selection to this entry
                    selected.clear();
                    selected.add(raw);
                    anchor[0] = raw;
                }
                // Ctrl+arrow only moves the cursor, leaving the selection as it is
                cursor[0] = raw;
                int displayIndex = displayIndexOf(raw);
                if (displayIndex >= 0) {
                    listView.getFocusModel().focus(displayIndex);
                    listView.scrollTo(displayIndex);
                }
                e.consume();
            } else if (key == KeyCode.SPACE) {
                if (position >= 0) {
                    String raw = order.get(position);
                    toggle(selected, raw);
                    anchor[0] = raw;
                }
                e.consume();
            } else if (key == KeyCode.A && e.isControlDown()) {
                selected.clear();
                selected.addAll(order);
                e.consume();
            }
        });

        // ---- Keep the summary, buttons, list highlight and export label in step with the selection
        Runnable updateSummary = () -> {
            List<HistoryRecord> picked = selectedRecords(selected);
            int chosen = picked.size();
            int shown = entryOrder().size();
            summaryValue.setText(chosen + " of " + shown);
            resultCount.setText("Showing " + shown + " of " + historyList.size());
            deleteBtn.setDisable(chosen == 0);
            selectAllBtn.setDisable(shown == 0);
            sendBtn.setDisable(chosen != 1);
            pinBtn.setDisable(picked.stream().allMatch(r -> PinStore.isPinned(r.raw)));
            exportSelectedBtn.setText("Export Selected (" + chosen + ")");
            exportSelectedBtn.setDisable(chosen == 0);
        };
        selected.addListener((SetChangeListener<String>) change -> {
            syncNativeSelection(listView, selected);
            listView.refresh();
            updateSummary.run();
        });
        // The list's own highlight follows our selection, and is redrawn when the entries change
        displayList.addListener((ListChangeListener<Object>) change -> {
            syncNativeSelection(listView, selected);
            updateSummary.run();
        });

        // ---- Filters. While `syncing` is set, a control is being changed by code, so the filter is not re-run.
        boolean[] syncing = new boolean[1];
        Runnable pushFilter = () -> {
            if (syncing[0]) {
                return;
            }
            boolean timesOk = true;
            LocalTime timeFrom = null;
            LocalTime timeTo = null;
            try {
                timeFrom = HistoryFilter.parseTime(timeFromField.getText());
                UiUtil.clearInvalid(timeFromField);
            } catch (DateTimeParseException ex) {
                UiUtil.markInvalid(timeFromField);
                timesOk = false;
            }
            try {
                timeTo = HistoryFilter.parseTime(timeToField.getText());
                UiUtil.clearInvalid(timeToField);
            } catch (DateTimeParseException ex) {
                UiUtil.markInvalid(timeToField);
                timesOk = false;
            }
            filterNote.setText(timesOk ? "" : "Times need the 24-hour form, like 08:30. They are ignored until fixed.");
            filterNote.setVisible(!timesOk);
            filterNote.setManaged(!timesOk);

            String calculator = showCombo.getValue();
            applyFilter(new HistoryFilter(
                    calculator == null || ALL_CALCULATIONS.equals(calculator) ? null : calculator,
                    searchField.getText(),
                    fromPicker.getValue(), toPicker.getValue(),
                    timeFrom, timeTo,
                    HistoryFilter.SORT_OLDEST.equals(sortCombo.getValue())), selected);
        };

        searchField.textProperty().addListener((obs, oldText, newText) -> pushFilter.run());
        timeFromField.textProperty().addListener((obs, oldText, newText) -> pushFilter.run());
        timeToField.textProperty().addListener((obs, oldText, newText) -> pushFilter.run());
        sortCombo.valueProperty().addListener((obs, oldSort, newSort) -> pushFilter.run());
        showCombo.valueProperty().addListener((obs, oldShow, newShow) -> pushFilter.run());

        // A preset fills the two dates; typing a date by hand turns the period into a custom range
        periodCombo.valueProperty().addListener((obs, oldPeriod, period) -> {
            if (syncing[0]) {
                return;
            }
            LocalDate[] range = HistoryFilter.range(period, LocalDate.now());
            if (range != null) {
                syncing[0] = true;
                try {
                    fromPicker.setValue(range[0]);
                    toPicker.setValue(range[1]);
                } finally {
                    syncing[0] = false;
                }
            }
            pushFilter.run();
        });
        fromPicker.valueProperty().addListener((obs, oldDate, date) -> {
            if (!syncing[0]) {
                syncing[0] = true;
                try {
                    periodCombo.setValue(HistoryFilter.PERIOD_CUSTOM);
                } finally {
                    syncing[0] = false;
                }
                pushFilter.run();
            }
        });
        toPicker.valueProperty().addListener((obs, oldDate, date) -> {
            if (!syncing[0]) {
                syncing[0] = true;
                try {
                    periodCombo.setValue(HistoryFilter.PERIOD_CUSTOM);
                } finally {
                    syncing[0] = false;
                }
                pushFilter.run();
            }
        });

        clearFiltersBtn.setOnAction(e -> {
            syncing[0] = true;
            try {
                searchField.clear();
                timeFromField.clear();
                timeToField.clear();
                fromPicker.setValue(null);
                toPicker.setValue(null);
                periodCombo.setValue(HistoryFilter.PERIOD_ALL);
                sortCombo.setValue(HistoryFilter.SORT_NEWEST);
                showCombo.setValue(ALL_CALCULATIONS);
            } finally {
                syncing[0] = false;
            }
            UiUtil.clearInvalid(timeFromField, timeToField);
            filterNote.setVisible(false);
            filterNote.setManaged(false);
            pushFilter.run();
        });

        // The Show list follows the calculations that exist. A calculator that has gone is dropped from it.
        Runnable refreshShow = () -> {
            String previous = showCombo.getValue();
            TreeSet<String> calculators = new TreeSet<>();
            for (String raw : historyList) {
                String calculator = HistoryRecord.parse(raw).calculator;
                if (!calculator.isEmpty()) {
                    calculators.add(calculator);
                }
            }
            List<String> items = new ArrayList<>();
            items.add(ALL_CALCULATIONS);
            items.addAll(calculators);
            String keep = items.contains(previous) ? previous : ALL_CALCULATIONS;

            syncing[0] = true;
            try {
                showCombo.getItems().setAll(items);
                showCombo.setValue(keep);
            } finally {
                syncing[0] = false;
            }
            pushFilter.run();
            clearBtn.setDisable(historyList.isEmpty());
        };
        historyList.addListener((ListChangeListener<String>) change -> refreshShow.run());
        refreshShow.run();
        updateSummary.run();

        // ---- Layout: side by side when the tab is wide, the action card under the ledger when it is narrow
        StackPane holder = new StackPane();
        Pane[] layout = new Pane[1];
        layout[0] = buildLedgerLayout(false, ledger, card);
        holder.getChildren().add(layout[0]);
        boolean[] sideBySide = new boolean[1];
        holder.widthProperty().addListener((obs, oldWidth, newWidth) -> {
            boolean wide = newWidth.doubleValue() >= SIDE_BY_SIDE_MIN;
            if (wide == sideBySide[0]) {
                return;
            }
            sideBySide[0] = wide;
            layout[0].getChildren().clear(); // a node can only have one parent, so detach them first
            layout[0] = buildLedgerLayout(wide, ledger, card);
            holder.getChildren().setAll(layout[0]);
        });

        // The header sits above the ledger and card, with the calculator pages' spacing and padding
        VBox page = new VBox(14, crumb, heading, statusLabel, holder);
        page.setPadding(new Insets(20));
        VBox.setVgrow(holder, Priority.ALWAYS);

        // Scroll if the window is short, but otherwise let the ledger fill the height of the tab
        ScrollPane scroll = new ScrollPane(page);
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("tab-scroll");

        StackPane rootWrapper = new StackPane(scroll);
        rootWrapper.setPadding(new Insets(10));
        rootWrapper.getStyleClass().removeAll("light-theme", "dark-theme");
        rootWrapper.getStyleClass().add(darkTheme ? "dark-theme" : "light-theme");
        return rootWrapper;
    }

    /** Applies a filter to the ledger, and drops any ticked entry that the filter now hides. */
    private static void applyFilter(HistoryFilter next, ObservableSet<String> selected) {
        filter = next;
        rebuildDisplay();
        for (String raw : new ArrayList<>(selected)) {
            if (displayIndexOf(raw) < 0) {
                selected.remove(raw);
            }
        }
    }

    /** A labelled control for the filter grid. */
    private static VBox filterCell(String label, Node control) {
        Label caption = new Label(label);
        caption.getStyleClass().add("filter-label");
        if (control instanceof Region region) {
            region.setMaxWidth(Double.MAX_VALUE);
        }
        return new VBox(5, caption, control);
    }

    /** The ledger and the action card: side by side when wide, the card under the ledger when narrow. */
    private static Pane buildLedgerLayout(boolean wide, Region ledger, Region card) {
        if (wide) {
            // The ledger and the card share the width in proportion, so both grow and shrink with the window.
            // The card never drops below its minimum, so its buttons and labels stay whole.
            GridPane row = new GridPane();
            row.setHgap(20);
            ColumnConstraints ledgerColumn = new ColumnConstraints();
            ledgerColumn.setPercentWidth(64);
            ledgerColumn.setHgrow(Priority.ALWAYS);
            ColumnConstraints cardColumn = new ColumnConstraints();
            cardColumn.setPercentWidth(36);
            cardColumn.setMinWidth(300);
            cardColumn.setHgrow(Priority.ALWAYS);
            row.getColumnConstraints().addAll(ledgerColumn, cardColumn);
            card.setMaxWidth(Double.MAX_VALUE);
            GridPane.setHgrow(ledger, Priority.ALWAYS);
            GridPane.setVgrow(ledger, Priority.ALWAYS);
            GridPane.setHgrow(card, Priority.ALWAYS);
            GridPane.setVgrow(card, Priority.ALWAYS);
            row.add(ledger, 0, 0);
            row.add(card, 1, 0);
            return row;
        }
        card.setPrefWidth(Region.USE_COMPUTED_SIZE);
        card.setMaxWidth(Double.MAX_VALUE);
        VBox column = new VBox(20, ledger, card);
        VBox.setVgrow(ledger, Priority.ALWAYS);
        return column;
    }

    /** Buttons fill the width of their column. */
    private static void fillWidth(Region... nodes) {
        for (Region node : nodes) {
            node.setMaxWidth(Double.MAX_VALUE);
        }
    }

    private static Label sectionLabel(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }

    /** Makes the list's highlight match our selection, so the two never disagree. */
    private static void syncNativeSelection(ListView<Object> listView, ObservableSet<String> selected) {
        listView.getSelectionModel().clearSelection();
        for (String raw : selected) {
            int index = displayIndexOf(raw);
            if (index >= 0) {
                listView.getSelectionModel().select(index);
            }
        }
    }

    private static HistoryExporter.Format chosenFormat(ComboBox<String> formatCombo) {
        int index = Math.max(0, formatCombo.getSelectionModel().getSelectedIndex());
        return HistoryExporter.Format.values()[index];
    }

    private static void exportHistory(HistoryExporter.Format format, Window owner, Label statusLabel,
                                      List<HistoryRecord> records, String scope) {
        if (records.isEmpty()) {
            showStatus(statusLabel, "Nothing to export. Tick entries first, or run a calculation.", false);
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export calculation history");
        // Start in the export folder from Settings, or the home folder if none is set
        File folder = new File(AppSettings.exportFolder().isEmpty()
                ? System.getProperty("user.home") : AppSettings.exportFolder());
        if (folder.isDirectory()) {
            chooser.setInitialDirectory(folder);
        }
        chooser.setInitialFileName("marginiq_" + scope + "_" + LocalDate.now() + "." + format.extension);
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter(format.description, "*." + format.extension));

        File chosen = chooser.showSaveDialog(owner);
        if (chosen == null) {
            return; // user cancelled
        }

        Path path = chosen.toPath();
        if (!chosen.getName().toLowerCase().endsWith("." + format.extension)) {
            path = path.resolveSibling(chosen.getName() + "." + format.extension);
        }

        try {
            HistoryExporter.write(format, path, records);
            showStatus(statusLabel, "Exported " + records.size() + " entries to " + path, true);
        } catch (IOException | RuntimeException ex) {
            showStatus(statusLabel, "Export failed: " + ex.getMessage(), false);
        }
    }

    private static void showStatus(Label label, String text, boolean success) {
        label.getStyleClass().removeAll("error-label", "info-label");
        label.getStyleClass().add(success ? "info-label" : "error-label");
        label.setText(text);
        label.setVisible(!text.isEmpty());
        label.setManaged(!text.isEmpty());
    }

    private static ThreadPoolExecutor createSaver() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable, "marginiq-history-save");
                    thread.setDaemon(false); // the JVM waits for a pending save before it exits
                    return thread;
                });
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    /** Queues a save of the whole history. The list is copied now, and the database is written in the background. */
    private static void saveHistoryToFile() {
        List<String> snapshot = List.copyOf(historyList);
        SAVER.execute(() -> writeHistoryFile(snapshot));
    }

    /** Writes the whole history to the database in one transaction. Skipped when the history was not read. */
    private static void writeHistoryFile(List<String> lines) {
        if (!storageReady) {
            Notices.addOnce("history-save", "The calculation history was not saved, because it could not be read from "
                    + "the database. Press Refresh (F5), or restart MarginIQ Suite.");
            return;
        }
        try {
            AppDatabase.get().replaceHistory(lines);
            Notices.clearOnce("history-save");
        } catch (SQLException | RuntimeException ex) {
            Notices.addOnce("history-save", "The calculation history could not be saved to the database, so your latest "
                    + "changes may not be kept. (" + ex.getMessage() + ")");
        }
    }

    /** Reads the saved history from the database, newest first. */
    private static void loadHistory() {
        try {
            historyList.setAll(AppDatabase.get().historyLines());
            storageReady = true;
            Notices.clearOnce("history-read");
            Notices.clearOnce("history-save");
        } catch (SQLException | RuntimeException ex) {
            storageReady = false;
            Notices.addOnce("history-read", "The calculation history could not be read from the database, so nothing "
                    + "will be saved until you press Refresh (F5), or restart MarginIQ Suite. (" + ex.getMessage() + ")");
        }
    }

    /** The message shown when a change cannot be saved because the history was not read from the database. */
    public static final String CANNOT_CHANGE = "The history cannot be changed, because it could not be read from the "
            + "database. Press Refresh (F5), or restart MarginIQ Suite.";

    /** True when the history was read from the database, so a change can be saved. */
    public static boolean canChange() {
        return storageReady;
    }

    /** Stops saving the history until it is read again. Used while a restore replaces the database. */
    public static void forgetStorage() {
        storageReady = false;
    }

    /**
     * Reads the history from the database again, after any pending save has finished, and applies the retention
     * setting. Used by the Refresh button and after a restore.
     */
    public static void reload() {
        try {
            SAVER.submit(() -> { }).get(10, TimeUnit.SECONDS);
        } catch (Exception ex) {
            // A save that takes too long is left to finish; the history is read either way
        }
        loadHistory();
        applyRetention();
    }

}
