package application;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.application.Platform;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * The Business suite. A sidebar lists the tools in groups, with a search box and a Home page.
 * One tool is shown at a time in the content area on the right.
 */
public class BusinessTab {

    private static final int RECENT_COUNT = 5;

    /** Which tool opens each kind of saved calculation, by the name the calculator logs it under. */
    private static final Map<String, String> TOOL_FOR_HISTORY = Map.of(
            "Business - Margin", "Margin & Markup",
            "Business - Merchant Fee", "Merchant Fee",
            "Business - VAT", "VAT",
            "Business - SARS PAYE", "SARS PAYE",
            "Business - Freelance Rate", "Freelance Rate",
            "Business - Retirement", "Retirement Contributions",
            "Business - Logistics", "Volumetric Shipping",
            "Business - Interest Engine", "Simple vs. Compound Interest",
            "Business - Loan Repayment", "Loan & Bond");

    /** Size of each tool card on the Home page. Fixed, so the cards line up in a neat grid. */
    private static final double CARD_WIDTH = 260;
    private static final double CARD_HEIGHT = 150;
    private static final double BADGE_SIZE = 40;
    private static final double HOME_ICON_SCALE = 0.9;
    private static final double SIDEBAR_ICON_SCALE = 0.75;

    /** One tool: its name, a short description for the Home page, its page, and its sidebar button. */
    private static final class Tool {
        final String name;
        final String description;
        final Node page;
        final IconLibrary.Icon icon;
        IconLibrary.Category category = IconLibrary.Category.PLAIN;
        ToggleButton button;

        Tool(String name, String description, Node page, IconLibrary.Icon icon) {
            this.name = name;
            this.description = description;
            this.page = page;
            this.icon = icon;
        }
    }

    /** A named group of tools in the sidebar. */
    private static final class Section {
        final String title;
        final List<Tool> tools = new ArrayList<>();
        Label titleLabel;
        VBox box;

        Section(String title) {
            this.title = title;
        }
    }

    private final BorderPane rootPane = new BorderPane();
    private final StackPane contentArea = new StackPane();
    private final ToggleGroup navGroup = new ToggleGroup();
    private final TextField searchField = new TextField();
    private final List<Section> sections = new ArrayList<>();
    private final List<Tool> allTools = new ArrayList<>();
    private final VBox recentBox = new VBox(6);
    /** Re-sizes each fixed-size piece (Home cards, badges) when the text size changes. */
    private final List<Runnable> sizers = new ArrayList<>();
    private final VBox pinnedBox = new VBox(8);
    private final Label pinnedStatus = new Label();
    private final Label statsLabel = new Label();
    private Tool historyTool;
    private ToggleButton homeButton;
    private StackPane homePane;
    private StackPane settingsPage;
    private ScrollPane sidebarScroll;
    private Button toggleSidebarBtn;
    /** Below this width (unscaled pixels) the sidebar is icons only. The width is kept between the icons-only and full widths. */
    private static final int COMPACT_BELOW = 150;
    /** The sidebar's width in unscaled pixels, and the full width it returns to when it is expanded again. */
    private int sidebarPx = AppSettings.MAX_SIDEBAR_WIDTH;
    private int expandedPx = AppSettings.MAX_SIDEBAR_WIDTH;
    private boolean sidebarCompact = false;
    /** Names, shortcut badges, headings and the search box: hidden in the icons-only sidebar. */
    private final List<Node> sidebarTextParts = new ArrayList<>();
    /** The row inside each sidebar button. It is centred in the icons-only sidebar. */
    private final List<HBox> sidebarRows = new ArrayList<>();
    private final List<ToggleButton> sidebarButtons = new ArrayList<>();
    private VBox sidebarList;
    /** The tools the sidebar currently shows, in order. The search decides them. */
    private final List<Tool> visibleTools = new ArrayList<>();

    public BusinessTab(boolean darkTheme) {
        // Problems found on other threads, or while the app starts, are shown as one popup on the screen
        Notices.addListener(() -> Platform.runLater(BusinessTab::showNotices));
        Platform.runLater(BusinessTab::showNotices);
        rootPane.setPadding(new Insets(10));

        // ---- Tools, grouped into sidebar sections
        addSection("Pricing & Fees",
                tool("Margin & Markup", "Profit, margin and markup from cost and price",
                        wrapInScroll(MarginCalculator.createLayout(darkTheme)), IconLibrary.Icon.MARGIN),
                tool("Scenario Comparison", "Two pricing scenarios side by side, with the difference",
                        wrapInScroll(ScenarioCompare.createLayout(darkTheme)), IconLibrary.Icon.BARS),
                tool("Merchant Fee", "Card and EFT fees, and the charge for a target payout",
                        wrapInScroll(MerchantFeeCalculator.createLayout(darkTheme)), IconLibrary.Icon.CARD),
                tool("VAT", "Add or remove VAT from a price",
                        wrapInScroll(VatCalculator.createLayout(darkTheme)), IconLibrary.Icon.RECEIPT));

        addSection("Income & Tax",
                tool("SARS PAYE", "Estimated income tax on a salary, with medical credits and retirement",
                        wrapInScroll(SarsPayeEstimator.createLayout(darkTheme)), IconLibrary.Icon.BANKNOTE),
                tool("Freelance Rate", "Minimum hourly rate after tax, overheads and expenses",
                        wrapInScroll(FreelancerRateCalculator.createLayout(darkTheme)), IconLibrary.Icon.CLOCK),
                tool("Retirement Contributions", "Tax saved by retirement fund contributions (Section 11F)",
                        wrapInScroll(RetirementCalculator.createLayout(darkTheme)), IconLibrary.Icon.SHIELD));

        addSection("Shipping",
                tool("Volumetric Shipping", "Courier billable weight from box size",
                        wrapInScroll(VolumetricCalculator.createLayout(darkTheme)), IconLibrary.Icon.BOX));

        addSection("Savings & Loans",
                tool("Simple vs. Compound Interest", "Simple and compound growth, by years, months or days",
                        wrapInScroll(InterestCalculator.createLayout(darkTheme)), IconLibrary.Icon.BARS),
                tool("Loan & Bond", "Monthly instalment and total interest on a loan",
                        wrapInScroll(LoanCalculator.createLayout(darkTheme)), IconLibrary.Icon.CALENDAR));

        historyTool = tool("History & Tape", "Saved calculations, exported to PDF, Excel or CSV",
                BusinessHistory.createHistoryView(darkTheme, this::sendToCalculator), IconLibrary.Icon.LIST);
        addSection("Records", historyTool);

        addSection("Clients", tool("Client Profiles", "Saved clients with contact details, VAT choice and usual rates",
                wrapInScroll(ClientsView.createLayout(darkTheme)), IconLibrary.Icon.USERS));

        // Links between tools: a tool that sends figures to another asks for that tool to be opened
        CalcLinks.setOpener(name -> {
            Tool target = findTool(name);
            if (target != null) {
                openTool(target);
            }
        });

        // ---- Sidebar
        VBox sidebar = new VBox(4);
        sidebarList = sidebar;
        sidebar.setPadding(new Insets(12));
        sidebar.getStyleClass().add("sidebar");
        sidebar.setMaxHeight(Region.USE_PREF_SIZE);

        searchField.setPromptText("Search tools (Ctrl+F)");
        sidebarTextParts.add(searchField);
        searchField.textProperty().addListener((obs, oldText, newText) -> applyFilter(newText));
        searchField.setOnAction(e -> openFirstMatch());
        VBox.setMargin(searchField, new Insets(0, 0, 6, 0));

        homeButton = navButton("Home", "Ctrl+0", IconLibrary.Icon.HOME);
        homeButton.setOnAction(e -> openHome());
        sidebar.getChildren().addAll(searchField, homeButton);

        for (Section s : sections) {
            s.titleLabel = new Label(s.title.toUpperCase());
            s.titleLabel.getStyleClass().add("nav-section-title");
            sidebarTextParts.add(s.titleLabel);
            s.box = new VBox(4);
            s.box.setMaxHeight(Region.USE_PREF_SIZE);
            for (Tool t : s.tools) {
                t.button = navButton(t.name, shortcutOf(t), t.icon);
                t.button.setOnAction(e -> openTool(t));
            }
        }
        applyFilter("");

        sidebarScroll = new ScrollPane(sidebar);
        sidebarScroll.setFitToWidth(true);
        sidebarScroll.setFitToHeight(true);
        sidebarScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        // The scroll bar space is always reserved. Otherwise the bar appears and disappears as a search
        // shortens the list, which changes the sidebar width and shifts the buttons and the Home page.
        sidebarScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.ALWAYS);
        sidebarScroll.getStyleClass().addAll("tab-scroll", "sidebar-scroll");

        // The text size and typeface. The stylesheet overrides go onto the window once it exists (after the app's
        // own stylesheet), and every size that depends on the text follows the saved setting.
        rootPane.sceneProperty().addListener((obs, was, scene) -> {
            if (scene != null) {
                Platform.runLater(() -> FontStyle.apply(scene));
            }
        });
        FontSettings.addChangeListener(this::applyTextSettings);

        // ---- Home page and content area
        homePane = wrapInScroll(buildHome());

        // Settings: built once. The startup choices are the Home page and every tool.
        List<String> startupChoices = new ArrayList<>();
        startupChoices.add(AppSettings.HOME_VIEW);
        for (Tool t : allTools) {
            startupChoices.add(t.name);
        }
        settingsPage = SettingsView.createPage(darkTheme, startupChoices);
        contentArea.setPadding(new Insets(0, 0, 0, 12));

        // Keyboard shortcuts: Ctrl+F search, Ctrl+1 to Ctrl+9 open the tools, Ctrl+H opens History
        rootPane.addEventFilter(KeyEvent.KEY_PRESSED, this::handleShortcut);

        // Top bar above the tool pages: the button that hides or shows the sidebar, Refresh, and Settings
        toggleSidebarBtn = new Button("Collapse to Icons (Ctrl+B)", IconLibrary.icon(IconLibrary.Icon.SIDEBAR, 0.8));
        toggleSidebarBtn.getStyleClass().add("sidebar-toggle");
        toggleSidebarBtn.setOnAction(e -> toggleSidebar());
        // Refresh reads everything saved again, so changes show without a restart. F5 does the same.
        Button refreshBtn = new Button("Refresh (F5)", IconLibrary.icon(IconLibrary.Icon.REFRESH, 0.8));
        refreshBtn.getStyleClass().add("pill-button");
        refreshBtn.setOnAction(e -> DataRefresh.reloadAll());
        refreshBtn.setTooltip(new Tooltip("Reads the saved settings, clients and history again. "
                + "The note at the top of the screen says when this is needed."));
        // Settings sits at the right of the same bar, so all preferences live in one place
        Button settingsBtn = new Button("Settings (Ctrl+,)", IconLibrary.icon(IconLibrary.Icon.GEAR, 0.8));
        settingsBtn.getStyleClass().add("pill-button");
        settingsBtn.setOnAction(e -> openSettings());
        Region barSpacer = new Region();
        HBox.setHgrow(barSpacer, Priority.ALWAYS);
        HBox topBar = new HBox(10, toggleSidebarBtn, refreshBtn, barSpacer, settingsBtn);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setPadding(new Insets(0, 0, 8, 12));

        // Says what is waiting for the user: a restart, or a Refresh (F5). It is hidden when nothing is waiting.
        Label waitingNote = new Label();
        waitingNote.getStyleClass().add("info-note");
        waitingNote.setWrapText(true);
        waitingNote.setMaxWidth(Double.MAX_VALUE);
        waitingNote.setVisible(false);
        waitingNote.setManaged(false);
        Runnable showWaiting = () -> {
            java.util.List<String> restart = PendingChanges.waitingFor(PendingChanges.Action.RESTART);
            java.util.List<String> refresh = PendingChanges.waitingFor(PendingChanges.Action.REFRESH);
            StringBuilder text = new StringBuilder();
            if (!restart.isEmpty()) {
                text.append("Restart MarginIQ Suite to apply: ").append(String.join(", ", restart)).append(".");
            }
            if (!refresh.isEmpty()) {
                if (text.length() > 0) {
                    text.append("  ");
                }
                text.append("Press Refresh (F5) to apply: ").append(String.join(", ", refresh)).append(".");
            }
            waitingNote.setText(text.toString());
            waitingNote.setVisible(text.length() > 0);
            waitingNote.setManaged(text.length() > 0);
        };
        AppSettings.addChangeListener(showWaiting);
        PendingChanges.addChangeListener(showWaiting);
        showWaiting.run();
        VBox topArea = new VBox(6, topBar, waitingNote);

        BorderPane centerPane = new BorderPane();
        centerPane.setTop(topArea);
        centerPane.setCenter(contentArea);

        // The sidebar's right edge is a handle: drag it to resize the sidebar, from icons only up to the full width
        Region splitter = new Region();
        splitter.setMinWidth(6);
        splitter.setPrefWidth(6);
        splitter.setMaxWidth(6);
        splitter.setCursor(Cursor.H_RESIZE);
        splitter.setStyle("-fx-background-color: rgba(128, 128, 128, 0.35);");
        Tooltip.install(splitter, new Tooltip("Drag to resize the sidebar"));
        double[] dragStart = new double[2];
        splitter.setOnMousePressed(e -> {
            dragStart[0] = e.getScreenX();
            dragStart[1] = sidebarScroll.getWidth();
        });
        splitter.setOnMouseDragged(e -> dragTo(dragStart[1] + (e.getScreenX() - dragStart[0])));
        splitter.setOnMouseReleased(e -> saveWidth());
        HBox sidebarArea = new HBox(sidebarScroll, splitter);
        HBox.setHgrow(sidebarScroll, Priority.NEVER);

        rootPane.setLeft(sidebarArea);
        rootPane.setCenter(centerPane);
        updateTheme(darkTheme);

        // The width saved last time. The sidebar starts icons only if Settings asks for that.
        int saved = AppSettings.sidebarWidth();
        expandedPx = saved >= COMPACT_BELOW ? saved : AppSettings.MAX_SIDEBAR_WIDTH;
        applyWidth(AppSettings.sidebarCollapsed() ? AppSettings.MIN_SIDEBAR_WIDTH : saved);
        openStartupView();
    }

    // ------------------------------------------------------------ building

    private static Tool tool(String name, String description, Node page, IconLibrary.Icon icon) {
        return new Tool(name, description, page, icon);
    }

    private void addSection(String title, Tool... tools) {
        Section section = new Section(title);
        for (Tool t : tools) {
            t.category = IconLibrary.categoryFor(title);
            section.tools.add(t);
            allTools.add(t);
        }
        sections.add(section);
    }

    /**
     * A sidebar button: the tool's icon, then its name. The shortcut is not shown here; it appears in the
     * tooltip when the mouse rests on the button. A button cannot place its graphic across its full width
     * by itself, so the row inside it is bound to the button's width.
     */
    private ToggleButton navButton(String name, String shortcut, IconLibrary.Icon icon) {
        ToggleButton button = new ToggleButton();
        button.setToggleGroup(navGroup);
        button.getStyleClass().add("nav-button");
        button.setMaxWidth(Double.MAX_VALUE);
        // Never taller than its content, even when a search leaves spare room in the sidebar
        button.setMaxHeight(Region.USE_PREF_SIZE);
        button.setAlignment(Pos.CENTER_LEFT);

        Label nameLabel = new Label(name);
        nameLabel.getStyleClass().add("nav-name");
        // No wrapping: the name stays on one line, so the button's height never depends on its width
        nameLabel.setWrapText(false);
        nameLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(nameLabel, Priority.ALWAYS);

        HBox row = new HBox(12, IconLibrary.icon(icon, SIDEBAR_ICON_SCALE), nameLabel);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setMaxHeight(Region.USE_PREF_SIZE);
        sidebarRows.add(row);
        sidebarTextParts.add(nameLabel);
        // The button's padding is 12px on each side, so the row is 24px narrower than the button
        row.prefWidthProperty().bind(button.widthProperty().subtract(24));
        button.setGraphic(row);
        sidebarButtons.add(button);
        // Always shows the tool and its shortcut when the mouse rests on the button
        button.setTooltip(new Tooltip(shortcut.isEmpty() ? name : name + "  (" + shortcut + ")"));
        return button;
    }


    /** The Home page: a title and stats, the tools grouped as in the sidebar, then recent calculations. */
    private VBox buildHome() {
        Label title = new Label("MarginIQ Business Tools");
        title.getStyleClass().add("home-title");
        Label intro = new Label("Pick a tool to open it, or search in the sidebar. "
                + "Ctrl+1 to Ctrl+9 open the first nine tools straight away.");
        intro.getStyleClass().add("home-intro");
        intro.setWrapText(true);
        statsLabel.getStyleClass().add("home-stats");

        VBox page = new VBox(24, title, intro, statsLabel);

        // One group per sidebar section, with a card for each tool in it
        for (Section s : sections) {
            Label heading = new Label(s.title.toUpperCase());
            heading.getStyleClass().add("home-section-title");
            FlowPane grid = new FlowPane(16, 16);
            for (Tool t : s.tools) {
                grid.getChildren().add(homeCard(t));
            }
            page.getChildren().add(new VBox(10, heading, grid));
        }

        // Pinned calculations: the ones pinned from History & Tape. Each one opens its calculator again.
        Label pinnedTitle = new Label("PINNED");
        pinnedTitle.getStyleClass().add("home-section-title");
        Label pinnedHint = new Label("Calculations pinned from History & Tape. Open one to fill its calculator in again.");
        pinnedHint.getStyleClass().add("home-intro");
        pinnedHint.setWrapText(true);
        pinnedStatus.getStyleClass().add("output-label");
        pinnedStatus.setWrapText(true);
        refreshPinned();
        PinStore.addChangeListener(this::refreshPinned);
        VBox pinnedCard = new VBox(10, pinnedTitle, pinnedHint, pinnedBox, pinnedStatus);
        pinnedCard.getStyleClass().add("home-panel");
        page.getChildren().add(pinnedCard);

        Label recentTitle = new Label("ACTIVITY FEED");
        recentTitle.getStyleClass().add("home-section-title");
        Hyperlink seeAll = new Hyperlink("See all in History & Tape");
        seeAll.setOnAction(e -> openTool(historyTool));

        refreshRecent();
        BusinessHistory.getHistory().addListener((ListChangeListener<String>) change -> refreshRecent());
        // A new number style in Settings changes the feed too
        AppSettings.addChangeListener(this::refreshRecent);

        Label feedHint = new Label("Your last five calculations. Open History & Tape for the full list.");
        feedHint.getStyleClass().add("home-intro");
        feedHint.setWrapText(true);
        VBox recentCard = new VBox(10, recentTitle, feedHint, recentBox, seeAll);
        recentCard.getStyleClass().add("home-panel");
        page.getChildren().add(recentCard);

        page.setPadding(new Insets(24));
        page.setMaxWidth(980);
        return page;
    }

    /**
     * A card for one tool: an icon badge beside its name, a short description, and its shortcut.
     * Every card is the same size.
     */
    private Button homeCard(Tool t) {
        // A tinted rounded square in the section's colour, with the tool's icon inside
        StackPane badge = new StackPane(IconLibrary.icon(t.icon, HOME_ICON_SCALE));
        badge.getStyleClass().addAll("icon-badge", t.category.badgeClass);
        keepSized(badge, BADGE_SIZE, BADGE_SIZE);

        Label name = new Label(t.name);
        name.getStyleClass().add("home-card-title");
        name.setWrapText(true);
        name.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(name, Priority.ALWAYS);

        HBox header = new HBox(10, badge, name);
        header.setAlignment(Pos.CENTER_LEFT);

        Label desc = new Label(t.description);
        desc.getStyleClass().add("home-card-desc");
        desc.setWrapText(true);
        Label keys = new Label(shortcutOf(t));
        keys.getStyleClass().add("home-card-shortcut");

        // The spacer pushes the shortcut to the bottom of the card
        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        VBox content = new VBox(6, header, desc, spacer, keys);
        keepSized(content, CARD_WIDTH - 32, CARD_HEIGHT - 28);

        Button card = new Button();
        card.setGraphic(content);
        card.setAlignment(Pos.TOP_LEFT);
        keepSized(card, CARD_WIDTH, CARD_HEIGHT);
        card.getStyleClass().add("home-card");
        card.setOnAction(e -> openTool(t));
        return card;
    }

    /** The keyboard shortcut for a tool, e.g. "Ctrl+3", or an empty string if it has none. */
    private String shortcutOf(Tool t) {
        if (t == historyTool) {
            return "Ctrl+H";
        }
        if (t.name.equals("Loan & Bond")) {
            return "Ctrl+L";
        }
        if (t.name.equals("Client Profiles")) {
            return "Ctrl+P";
        }
        int index = allTools.indexOf(t);
        return index >= 0 && index < 9 ? "Ctrl+" + (index + 1) : "";
    }

    /** Shows the pinned calculations on the Home page, each with Open and Unpin. */
    private void refreshPinned() {
        pinnedBox.getChildren().clear();
        List<String> lines = PinStore.all();
        if (lines.isEmpty()) {
            Label none = new Label("Nothing pinned yet. In History & Tape, select one saved calculation "
                    + "and press Pin to Home.");
            none.getStyleClass().add("output-label");
            none.setWrapText(true);
            pinnedBox.getChildren().add(none);
            return;
        }
        for (String raw : lines) {
            HistoryRecord record = HistoryRecord.parse(raw);
            if (record == null) {
                continue; // an unreadable line is skipped, not shown
            }
            // The text takes the full width and the buttons sit on their own line, so they are never squeezed
            Label line = new Label(record.displayLine());
            line.getStyleClass().add("recent-line");
            line.setWrapText(true);
            line.setMaxWidth(Double.MAX_VALUE);

            Button open = new Button("Open calculator");
            open.setMinWidth(Region.USE_PREF_SIZE);
            open.setOnAction(e -> {
                String problem = sendToCalculator(record);
                pinnedStatus.setText(problem == null ? "" : problem);
            });
            Button unpin = new Button("Unpin");
            unpin.setMinWidth(Region.USE_PREF_SIZE);
            unpin.setOnAction(e -> {
                try {
                    PinStore.remove(raw);
                    pinnedStatus.setText("");
                } catch (java.io.IOException ex) {
                    pinnedStatus.setText("Could not unpin: " + ex.getMessage());
                }
            });

            HBox buttons = new HBox(8, open, unpin);
            buttons.setAlignment(Pos.CENTER_LEFT);
            VBox item = new VBox(8, line, buttons);
            item.setPadding(new Insets(10, 12, 10, 12));
            item.getStyleClass().add("pinned-item");
            pinnedBox.getChildren().add(item);
        }
    }

    private void refreshRecent() {
        recentBox.getChildren().clear();
        List<String> lines = BusinessHistory.getHistory();
        int saved = lines.size();
        statsLabel.setText(allTools.size() + " tools in " + sections.size() + " groups  ·  "
                + saved + " saved calculation" + (saved == 1 ? "" : "s"));
        if (lines.isEmpty()) {
            Label none = new Label("Nothing yet. Your calculations will appear here.");
            none.getStyleClass().add("output-label");
            recentBox.getChildren().add(none);
            return;
        }
        // The newest entry is at the front of the list, so the feed starts there and takes the first few
        for (int i = 0; i < lines.size() && i < RECENT_COUNT; i++) {
            HistoryRecord record = HistoryRecord.parse(lines.get(i));
            Label line = new Label(record == null ? lines.get(i) : record.displayLine());
            line.getStyleClass().add("recent-line");
            line.getStyleClass().add("feed-item");
            line.setWrapText(true);
            recentBox.getChildren().add(line);
        }
    }

    // ------------------------------------------------------------ navigation

    private void openHome() {
        homeButton.setSelected(true);
        contentArea.getChildren().setAll(homePane);
    }

    private void openTool(Tool t) {
        t.button.setSelected(true);
        contentArea.getChildren().setAll(t.page);
    }

    /** Opens the view chosen in Settings. If that tool no longer exists, the Home page opens instead. */
    private void openStartupView() {
        Tool start = findTool(AppSettings.startupView());
        if (start == null) {
            openHome();
        } else {
            openTool(start);
        }
    }

    /** Opens the tool with this name, if there is one. */
    private void openNamedTool(String name) {
        Tool t = findTool(name);
        if (t != null) {
            openTool(t);
        }
    }

    private Tool findTool(String name) {
        for (Tool t : allTools) {
            if (t.name.equals(name)) {
                return t;
            }
        }
        return null;
    }

    /** Shows the Settings page. No sidebar item is highlighted while it is open. */
    private void openSettings() {
        navGroup.selectToggle(null);
        contentArea.getChildren().setAll(settingsPage);
    }

    /**
     * Rebuilds the sidebar for the search: the matching tools under their section headings, stacked from
     * the top. The list is rebuilt rather than hidden and shown, so every button is laid out afresh and
     * keeps its exact size and spacing when it returns.
     */
    private void applyFilter(String text) {
        String query = text == null ? "" : text.trim().toLowerCase();
        visibleTools.clear();
        sidebarList.getChildren().clear();
        for (Section s : sections) {
            s.box.getChildren().clear();
        }
        sidebarList.getChildren().addAll(searchField, homeButton);

        boolean anyMatch = false;
        for (Section s : sections) {
            List<Tool> matches = new ArrayList<>();
            for (Tool t : s.tools) {
                if (query.isEmpty() || t.name.toLowerCase().contains(query)
                        || t.description.toLowerCase().contains(query)) {
                    matches.add(t);
                }
            }
            if (matches.isEmpty()) {
                continue;
            }
            anyMatch = true;
            s.box.getChildren().add(s.titleLabel);
            for (Tool t : matches) {
                s.box.getChildren().add(t.button);
                visibleTools.add(t);
            }
            sidebarList.getChildren().add(s.box);
        }
        if (!anyMatch) {
            Label none = new Label("No tools match \u201C" + text.trim() + "\u201D.");
            none.getStyleClass().add("output-label");
            none.setWrapText(true);
            none.setPadding(new Insets(12, 12, 0, 12));
            sidebarList.getChildren().add(none);
        }
    }

    /**
     * Opens a saved calculation in the calculator that made it, with its inputs filled in.
     * Returns null on success, or a message saying why it could not be opened.
     */
    private String sendToCalculator(HistoryRecord record) {
        String toolName = TOOL_FOR_HISTORY.get(record.calculator);
        Tool target = null;
        for (Tool t : allTools) {
            if (t.name.equals(toolName)) {
                target = t;
            }
        }
        if (target == null) {
            return "This calculation cannot be sent to a calculator.";
        }
        String problem = HistoryLoader.load(record);
        if (problem != null) {
            return problem;
        }
        openTool(target);
        return null;
    }

    /** Pressing Enter in the search box opens the first tool that matches. */
    private void openFirstMatch() {
        // Enter with nothing typed should not jump to a tool
        if (searchField.getText() == null || searchField.getText().isBlank()) {
            return;
        }
        if (!visibleTools.isEmpty()) {
            openTool(visibleTools.get(0));
        }
    }

    /**
     * Ctrl+F searches, Ctrl+0 opens Home, Ctrl+1 to Ctrl+9 open the tools in sidebar order, Ctrl+H opens History,
     * Ctrl+L opens Loan &amp; Bond, Ctrl+P opens Client Profiles, Ctrl+B hides or shows the sidebar.
     */
    private void handleShortcut(KeyEvent e) {
        if (e.getCode() == KeyCode.F5) {
            DataRefresh.reloadAll();
            e.consume();
            return;
        }
        if (!e.isControlDown()) {
            return;
        }
        KeyCode code = e.getCode();
        if (code == KeyCode.F) {
            // Searching needs the names in the sidebar, so expand it first if it is icons only
            if (sidebarCompact) {
                toggleSidebar();
            }
            searchField.requestFocus();
            searchField.selectAll();
            e.consume();
        } else if (code == KeyCode.B) {
            toggleSidebar();
            e.consume();
        } else if (code == KeyCode.COMMA) {
            openSettings();
            e.consume();
        } else if (code == KeyCode.H) {
            openTool(historyTool);
            e.consume();
        } else if (code == KeyCode.L) {
            openNamedTool("Loan & Bond");
            e.consume();
        } else if (code == KeyCode.P) {
            openNamedTool("Client Profiles");
            e.consume();
        } else if (code == KeyCode.DIGIT0 || code == KeyCode.NUMPAD0) {
            openHome();
            e.consume();
        } else {
            int index = digitIndex(code);
            if (index >= 0 && index < allTools.size()) {
                openTool(allTools.get(index));
                e.consume();
            }
        }
    }

    /**
     * Switches between icons only and the last full width. Going icons only remembers the width it had, and
     * expanding returns to it. The new width is saved.
     */
    private void toggleSidebar() {
        if (sidebarCompact) {
            applyWidth(expandedPx);
        } else {
            expandedPx = sidebarPx;
            applyWidth(AppSettings.MIN_SIDEBAR_WIDTH);
        }
        saveWidth();
    }

    /**
     * Sets the sidebar's width in unscaled pixels, kept between the icons-only width and the full width.
     * Below COMPACT_BELOW the sidebar is icons only, with each name shown as a tooltip instead.
     */
    private void applyWidth(int px) {
        sidebarPx = AppSettings.clampSidebarWidth(px);
        double width = scaled(sidebarPx);
        sidebarScroll.setMinWidth(scaled(AppSettings.MIN_SIDEBAR_WIDTH));
        sidebarScroll.setPrefWidth(width);
        sidebarScroll.setMaxWidth(width);
        setSidebarCompact(sidebarPx < COMPACT_BELOW);
    }

    /** Shows a drag while the mouse moves. The width is saved when the drag ends. */
    private void dragTo(double screenWidth) {
        applyWidth((int) Math.round(screenWidth / FontSettings.scale()));
    }

    /** Shows every waiting notice in one popup. Must run on the screen thread. */
    private static void showNotices() {
        List<String> messages = Notices.take();
        if (messages.isEmpty()) {
            return;
        }
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("MarginIQ Suite");
        alert.setHeaderText(messages.size() == 1 ? "Please check this" : "Please check these");
        alert.setContentText(String.join("\n\n", messages));
        alert.setResizable(true);
        alert.getDialogPane().setPrefWidth(560);
        alert.showAndWait();
    }

    /** Saves the width so it is remembered the next time the app opens. A save that fails is shown as a notice. */
    private void saveWidth() {
        try {
            AppSettings.saveSidebarWidth(sidebarPx);
        } catch (IOException ex) {
            Notices.addOnce("sidebar-width", "The sidebar width could not be saved: " + ex.getMessage());
        }
    }

    /**
     * Icons only, or the full sidebar. Icons only keeps every button and its icon; the names, headings and
     * search box are hidden. Each button's tooltip always shows its tool and shortcut.
     */
    private void setSidebarCompact(boolean compact) {
        sidebarCompact = compact;
        for (Node part : sidebarTextParts) {
            part.setVisible(!compact);
            part.setManaged(!compact);
        }
        for (HBox row : sidebarRows) {
            row.setAlignment(compact ? Pos.CENTER : Pos.CENTER_LEFT);
        }
        sidebarList.setPadding(compact ? new Insets(12, 8, 12, 8) : new Insets(12));
        // The full sidebar always keeps room for its scroll bar, so the buttons do not shift during a search
        sidebarScroll.setVbarPolicy(compact ? ScrollPane.ScrollBarPolicy.AS_NEEDED : ScrollPane.ScrollBarPolicy.ALWAYS);
        toggleSidebarBtn.setText(compact ? "Expand Sidebar (Ctrl+B)" : "Collapse to Icons (Ctrl+B)");
    }

    private static int digitIndex(KeyCode code) {
        return switch (code) {
            case DIGIT1, NUMPAD1 -> 0;
            case DIGIT2, NUMPAD2 -> 1;
            case DIGIT3, NUMPAD3 -> 2;
            case DIGIT4, NUMPAD4 -> 3;
            case DIGIT5, NUMPAD5 -> 4;
            case DIGIT6, NUMPAD6 -> 5;
            case DIGIT7, NUMPAD7 -> 6;
            case DIGIT8, NUMPAD8 -> 7;
            case DIGIT9, NUMPAD9 -> 8;
            default -> -1;
        };
    }

    // ------------------------------------------------------------ theme and helpers

    /** A pixel size at the current text size: 1.0 is the normal size, so 100 px at 120% is 120 px. */
    private static double scaled(double px) {
        return px * FontSettings.scale();
    }

    /** Sets a fixed size now, and again whenever the text size changes, so it grows and shrinks with the text. */
    private void keepSized(Region node, double width, double height) {
        Runnable apply = () -> {
            double w = scaled(width);
            double h = scaled(height);
            node.setMinSize(w, h);
            node.setPrefSize(w, h);
            node.setMaxSize(w, h);
        };
        sizers.add(apply);
        apply.run();
    }

    /** Applies the text size and typeface: the stylesheet, sidebar, Home cards and icons all follow it. */
    private void applyTextSettings() {
        Scene scene = rootPane.getScene();
        if (scene != null) {
            FontStyle.apply(scene);
        }
        for (Runnable sizer : sizers) {
            sizer.run();
        }
        applyWidth(sidebarPx);
        IconLibrary.refreshSizes();
    }

    /** Puts a calculator in a scroll pane so it never gets clipped in a small window. */
    private static StackPane wrapInScroll(VBox layout) {
        StackPane content = new StackPane(layout);
        StackPane.setAlignment(layout, Pos.TOP_CENTER);
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("tab-scroll");

        StackPane pane = new StackPane(scroll);
        pane.setPadding(new Insets(10));
        return pane;
    }

    /**
     * Sets the theme on the whole suite and on every page. The history page sets its own
     * theme class when it is built, so each page must be updated here, not just the outer pane.
     */
    public void updateTheme(boolean darkTheme) {
        String addTheme = darkTheme ? "dark-theme" : "light-theme";
        String removeTheme = darkTheme ? "light-theme" : "dark-theme";

        List<Node> nodes = new ArrayList<>();
        nodes.add(rootPane);
        nodes.add(homePane);
        nodes.add(settingsPage);
        for (Tool t : allTools) {
            nodes.add(t.page);
        }
        for (Node node : nodes) {
            node.getStyleClass().removeAll(removeTheme, addTheme);
            node.getStyleClass().add(addTheme);
        }
    }

    public BorderPane getRootPane() {
        return rootPane;
    }
}
