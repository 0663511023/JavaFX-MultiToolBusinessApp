package application;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

/**
 * The Settings page. Every value is checked before anything is saved, so a bad entry never
 * half-applies. The theme takes effect at once; the startup view applies the next time the app opens.
 */
final class SettingsView {

    private static final String LIGHT = "Light";
    private static final String DARK = "Dark";
    private static final String EXPANDED = "Full sidebar";
    private static final String COLLAPSED = "Icons only";
    private static final String NUMBER_SA = "South African (1 234,56)";
    private static final String NUMBER_STANDARD = "Standard (1,234.56)";
    /** The retention choices, in the same order as AppSettings.RETENTION_DAYS. */
    private static final String[] RETENTION_LABELS = {"Keep everything", "Last 30 days", "Last 90 days", "Last year"};

    private SettingsView() {}

    @FunctionalInterface
    private interface Checker<T> {
        T check(String text) throws InputParser.InvalidInputException;
    }

    /** Builds the page. startupChoices are the names offered for the startup view, Home first. */
    static StackPane createPage(boolean darkTheme, List<String> startupChoices) {
        Label crumb = new Label("MarginIQ Suite  ›  Settings");
        crumb.getStyleClass().add("breadcrumb");
        Label heading = new Label("Settings");
        heading.getStyleClass().add("page-title");

        Label errorLabel = UiUtil.createErrorLabel();
        Label statusLabel = new Label();
        statusLabel.getStyleClass().add("info-note");
        statusLabel.setWrapText(true);
        statusLabel.setVisible(false);
        statusLabel.setManaged(false);

        // ---- Controls
        ComboBox<String> themeCombo = fill(new ComboBox<>());
        themeCombo.getItems().addAll(LIGHT, DARK);
        ComboBox<String> numberCombo = fill(new ComboBox<>());
        numberCombo.getItems().addAll(NUMBER_SA, NUMBER_STANDARD);

        TextField vatField = new TextField();
        vatField.setPromptText("e.g. 15");
        TextField loanRateField = new TextField();
        loanRateField.setPromptText("e.g. 11.5");
        TextField loanYearsField = new TextField();
        loanYearsField.setPromptText("e.g. 20");

        ComboBox<String> startupCombo = fill(new ComboBox<>());
        startupCombo.getItems().addAll(startupChoices);
        ComboBox<String> sidebarCombo = fill(new ComboBox<>());
        sidebarCombo.getItems().addAll(EXPANDED, COLLAPSED);

        TextField folderField = new TextField();
        folderField.setPromptText("Empty: your home folder");
        Button browseBtn = new Button("Browse…");
        Button homeFolderBtn = new Button("Use home folder");
        ComboBox<String> retentionCombo = fill(new ComboBox<>());
        retentionCombo.getItems().addAll(RETENTION_LABELS);

        // Business and branding, printed on exports, quotes and invoices
        TextField nameField = new TextField();
        nameField.setPromptText("e.g. Thabo's Plumbing (Pty) Ltd");
        TextField streetField = new TextField();
        streetField.setPromptText("e.g. 12 Main Road");
        TextField suburbField = new TextField();
        suburbField.setPromptText("e.g. Rosebank");
        TextField postalField = new TextField();
        postalField.setPromptText("e.g. 2196");
        TextField phoneField = new TextField();
        phoneField.setPromptText("e.g. 082 123 4567");
        TextField emailField = new TextField();
        emailField.setPromptText("e.g. info@yourbusiness.co.za");
        TextField vatNumberField = new TextField();
        vatNumberField.setPromptText("10 digits, e.g. 4123456789 (optional)");
        TextField logoField = new TextField();
        logoField.setPromptText("No logo chosen");
        logoField.setEditable(false);
        Button logoBrowseBtn = new Button("Browse…");
        Button logoClearBtn = new Button("Remove");
        TextField footerField = new TextField();
        footerField.setPromptText("e.g. Generated via MarginIQ Suite (blank for none)");

        // Phone, email, postal code and VAT: wrong keys are refused, and each field is checked when left
        FieldCheck.contactInput(phoneField, emailField, postalField, vatNumberField);

        // Text size and typeface. Icons, the sidebar and the Home cards follow the size.
        Slider sizeSlider = new Slider(FontSettings.MIN_PERCENT, FontSettings.MAX_PERCENT, FontSettings.percent());
        sizeSlider.setMajorTickUnit(10);
        sizeSlider.setMinorTickCount(1);
        sizeSlider.setSnapToTicks(true);
        sizeSlider.setBlockIncrement(FontSettings.STEP);
        Label sizeReadout = new Label();
        ComboBox<String> familyCombo = fill(new ComboBox<>());
        familyCombo.getItems().addAll(FontSettings.FAMILY_NAMES);
        Label preview = new Label("The quick brown fox jumps over the lazy dog. R 1 234,56");
        preview.setWrapText(true);
        Runnable updatePreview = () -> {
            int percent = (int) Math.round(sizeSlider.getValue());
            sizeReadout.setText("Text size: " + percent + "%");
            int index = Math.max(0, familyCombo.getSelectionModel().getSelectedIndex());
            // 15 px is the normal size of the interface text, so the preview shows what the app will show
            double px = Math.round(15 * percent / 100.0 * 10) / 10.0;
            preview.setStyle("-fx-font-size: " + px + "px; -fx-font-family: \"" + FontSettings.FAMILY_CSS[index] + "\";");
        };
        sizeSlider.valueProperty().addListener((obs, a, b) -> updatePreview.run());
        familyCombo.valueProperty().addListener((obs, a, b) -> updatePreview.run());
        VBox textCard = card("TEXT & ICONS",
                CalcLayout.field(CalcLayout.label("Text size:"), sizeSlider),
                sizeReadout,
                CalcLayout.field(CalcLayout.label("Typeface:"), familyCombo),
                preview,
                CalcLayout.info("Icons, the sidebar and the Home cards grow and shrink with the text. Sizes run from "
                        + FontSettings.MIN_PERCENT + "% to " + FontSettings.MAX_PERCENT + "%. A typeface that is not "
                        + "installed on this computer falls back to the default."));

        // ---- Groups, each in the same card style as the calculators
        VBox appearance = card("APPEARANCE",
                CalcLayout.field(CalcLayout.label("Theme:"), themeCombo),
                CalcLayout.field(CalcLayout.label("Number style:"), numberCombo),
                CalcLayout.info("Changes how amounts are shown. You can type numbers in either style."),
                CalcLayout.info("The theme and text size apply at once."));

        VBox calculations = card("CALCULATION DEFAULTS",
                CalcLayout.field(CalcLayout.label("VAT rate (%):"), vatField),
                CalcLayout.field(CalcLayout.label("Default loan interest rate (%):"), loanRateField),
                CalcLayout.field(CalcLayout.label("Default loan term (years):"), loanYearsField),
                CalcLayout.info("The VAT rate applies at once. The loan defaults show on Loan & Bond after you "
                        + "restart MarginIQ Suite."));

        VBox startup = card("STARTUP",
                CalcLayout.field(CalcLayout.label("Open this view when the app starts:"), startupCombo),
                CalcLayout.field(CalcLayout.label("Sidebar when the app starts:"), sidebarCombo),
                CalcLayout.info("These apply the next time you open MarginIQ Suite."));

        // ---- Quote and invoice numbers. They have their own button, because they are not part of Save Settings.
        TextField nextQuoteField = new TextField();
        nextQuoteField.setPromptText("e.g. 12");
        TextField nextInvoiceField = new TextField();
        nextInvoiceField.setPromptText("e.g. 4");
        Label numbersNote = CalcLayout.info("");
        Label lastIssued = CalcLayout.info("");
        Label numbersError = UiUtil.createErrorLabel();
        Button confirmNumbersBtn = new Button("Confirm Numbers");
        confirmNumbersBtn.getStyleClass().add("button-primary");
        Runnable showNumbers = () -> {
            try {
                MarginDatabase db = AppDatabase.get();
                long quote = db.peekNumber(QuoteDocument.Kind.QUOTE);
                long invoice = db.peekNumber(QuoteDocument.Kind.INVOICE);
                nextQuoteField.setText(String.valueOf(quote));
                nextInvoiceField.setText(String.valueOf(invoice));
                lastIssued.setText("Last issued: " + issuedText(QuoteDocument.Kind.QUOTE, quote) + " for quotations, and "
                        + issuedText(QuoteDocument.Kind.INVOICE, invoice) + " for invoices.");
                showStatus(numbersNote, db.numbersConfirmed() ? ""
                        : "Quotations and invoices cannot be saved until you confirm the next numbers below. "
                        + "The numbers could not be read from your old files, so check them against your last issued documents.");
            } catch (SQLException ex) {
                UiUtil.showError(numbersError, "Could not read the numbers: " + ex.getMessage());
            }
        };
        showNumbers.run();
        VBox numbersCard = card("QUOTE & INVOICE NUMBERS",
                numbersNote,
                lastIssued,
                CalcLayout.field(CalcLayout.label("Next quotation number:"), nextQuoteField),
                CalcLayout.field(CalcLayout.label("Next invoice number:"), nextInvoiceField),
                numbersError,
                new HBox(10, confirmNumbersBtn),
                CalcLayout.info("A number is never used twice: the next number must be above the last one already issued. "
                        + "Changing these does not change any document that was already saved."));

        HBox folderRow = new HBox(8, folderField, browseBtn, homeFolderBtn);
        folderRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(folderField, Priority.ALWAYS);
        VBox data = card("DATA",
                CalcLayout.field(CalcLayout.label("Default folder for exports:"), folderRow),
                CalcLayout.field(CalcLayout.label("Keep calculation history:"), retentionCombo),
                CalcLayout.info("Older entries are moved to the archive in the MarginIQ database "
                        + "as they pass the limit. Nothing is deleted."),
                CalcLayout.info("After you change how long history is kept, press Refresh (F5) to apply it."));

        HBox logoRow = new HBox(8, logoField, logoBrowseBtn, logoClearBtn);
        logoRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(logoField, Priority.ALWAYS);
        VBox business = card("BUSINESS & BRANDING",
                CalcLayout.field(CalcLayout.label("Business name:"), nameField),
                CalcLayout.field(CalcLayout.label("Street address (optional):"), streetField),
                CalcLayout.field(CalcLayout.label("Suburb or city (optional):"), suburbField),
                CalcLayout.field(CalcLayout.label("Postal code (optional):"), postalField),
                CalcLayout.field(CalcLayout.label("Phone (optional):"), phoneField),
                CalcLayout.field(CalcLayout.label("Email (optional):"), emailField),
                CalcLayout.field(CalcLayout.label("VAT number (optional):"), vatNumberField),
                CalcLayout.field(CalcLayout.label("Logo (PNG or JPEG):"), logoRow),
                CalcLayout.field(CalcLayout.label("Footer note:"), footerField),
                CalcLayout.info("Printed at the top of exported reports, quotes and invoices. "
                        + "A logo is saved in the MarginIQ database, so it still prints if the original moves."));

        // ---- Backup and restore (its own buttons, so it is kept apart from Save Settings)
        VBox backupCard = BackupPanel.card();

        // ---- Buttons
        Button saveBtn = new Button("Save Settings");
        saveBtn.getStyleClass().add("button-primary");
        Button defaultsBtn = new Button("Restore Defaults");
        defaultsBtn.getStyleClass().add("pill-button");
        HBox buttons = new HBox(10, saveBtn, defaultsBtn);
        buttons.setAlignment(Pos.CENTER_LEFT);

        // Shows the saved values in the controls (also used after a save, to tidy the entries)
        Runnable showCurrent = () -> {
            themeCombo.setValue(AppSettings.isDarkTheme() ? DARK : LIGHT);
            numberCombo.setValue(Money.STANDARD.equals(AppSettings.numberStyle()) ? NUMBER_STANDARD : NUMBER_SA);
            vatField.setText(AppSettings.vatPercentText());
            loanRateField.setText(AppSettings.loanRateText());
            loanYearsField.setText(AppSettings.loanYearsText());
            startupCombo.setValue(startupChoices.contains(AppSettings.startupView())
                    ? AppSettings.startupView() : AppSettings.HOME_VIEW);
            sidebarCombo.setValue(AppSettings.sidebarCollapsed() ? COLLAPSED : EXPANDED);
            folderField.setText(AppSettings.exportFolder());
            retentionCombo.getSelectionModel().select(retentionIndex(AppSettings.retentionDays()));
            nameField.setText(Branding.current().businessName());
            ContactDetails contact = Branding.current().contact();
            streetField.setText(contact.street);
            suburbField.setText(contact.suburb);
            postalField.setText(contact.postalCode);
            phoneField.setText(ContactDetails.formatPhone(contact.phone));
            emailField.setText(contact.email);
            vatNumberField.setText(contact.vatNumber);
            logoField.setText(Branding.current().logoFile());
            footerField.setText(Branding.current().footerNote());
            sizeSlider.setValue(FontSettings.percent());
            familyCombo.getSelectionModel().select(FontSettings.familyIndex());
            UiUtil.clearInvalid(vatField, loanRateField, loanYearsField, folderField,
                    nameField, streetField, suburbField, postalField, phoneField, emailField,
                    vatNumberField, footerField);
        };
        showCurrent.run();

        // ---- Actions
        browseBtn.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Choose the default export folder");
            String typed = folderField.getText().trim();
            File start = new File(typed.isEmpty() ? System.getProperty("user.home") : typed);
            if (start.isDirectory()) {
                chooser.setInitialDirectory(start);
            }
            File chosen = chooser.showDialog(folderField.getScene().getWindow());
            if (chosen != null) {
                folderField.setText(chosen.getAbsolutePath());
            }
        });
        homeFolderBtn.setOnAction(e -> folderField.clear());
        logoBrowseBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Choose your logo");
            chooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("PNG or JPEG image", "*.png", "*.jpg", "*.jpeg"));
            File chosen = chooser.showOpenDialog(logoField.getScene().getWindow());
            if (chosen != null) {
                logoField.setText(chosen.getAbsolutePath());
            }
        });
        logoClearBtn.setOnAction(e -> logoField.clear());

        saveBtn.setOnAction(e -> {
            UiUtil.clearInvalid(vatField, loanRateField, loanYearsField, folderField,
                    nameField, streetField, suburbField, postalField, phoneField, emailField,
                    vatNumberField, footerField);
            showStatus(statusLabel, "");
            try {
                boolean dark = DARK.equals(themeCombo.getValue());
                BigDecimal vat = check(vatField, AppSettings::checkVatPercent);
                BigDecimal rate = check(loanRateField, AppSettings::checkLoanRate);
                BigDecimal years = check(loanYearsField, AppSettings::checkLoanYears);
                String folder = check(folderField, AppSettings::checkExportFolder);
                String name = check(nameField, Branding::checkName);
                // Each contact field is checked on its own, so a mistake is marked in the box where it was made
                String street = check(streetField, ContactDetails::checkStreet);
                String suburb = check(suburbField, ContactDetails::checkSuburb);
                String postal = check(postalField, ContactDetails::checkPostalCode);
                String phone = check(phoneField, ContactDetails::checkPhone);
                String email = check(emailField, ContactDetails::checkEmail);
                String vatNumber = check(vatNumberField, ContactDetails::checkVatNumber);
                ContactDetails details = new ContactDetails(street, suburb, postal, phone, email, vatNumber);
                String footer = check(footerField, Branding::checkFooter);
                String startupValue = startupCombo.getValue() == null ? AppSettings.HOME_VIEW : startupCombo.getValue();
                int index = Math.max(0, retentionCombo.getSelectionModel().getSelectedIndex());
                int retention = AppSettings.RETENTION_DAYS[index];

                String style = NUMBER_STANDARD.equals(numberCombo.getValue()) ? Money.STANDARD : Money.SA;
                // The logo is saved first, so a bad logo file stops the save before anything is written
                String logoText = logoField.getText().trim();
                String logo = logoText.isEmpty() || logoText.equals(Branding.current().logoFile())
                        ? logoText : Branding.importLogo(logoText);
                Branding brand = Branding.current().withDetails(name, details, logo, footer);
                AppSettings.update(dark, vat, rate, years, startupValue,
                        COLLAPSED.equals(sidebarCombo.getValue()), folder, retention, style);
                brand.save();
                FontSettings.update((int) Math.round(sizeSlider.getValue()),
                        Math.max(0, familyCombo.getSelectionModel().getSelectedIndex()));
                UiUtil.showError(errorLabel, "");
                showCurrent.run();
                showStatus(statusLabel, "Settings saved. Anything still waiting is shown at the top of the screen.");
            } catch (InputParser.InvalidInputException ex) {
                UiUtil.showError(errorLabel, "Error: " + ex.getMessage());
            } catch (IOException ex) {
                UiUtil.showError(errorLabel, "Could not save settings: " + ex.getMessage());
            }
        });

        defaultsBtn.setOnAction(e -> {
            try {
                // The theme is kept as it is: restoring defaults should not change how the app looks
                AppSettings.update(AppSettings.isDarkTheme(), AppSettings.DEFAULT_VAT_PERCENT,
                        AppSettings.DEFAULT_LOAN_RATE, AppSettings.DEFAULT_LOAN_YEARS,
                        AppSettings.HOME_VIEW, false, "", 0, AppSettings.DEFAULT_NUMBER_STYLE);
                FontSettings.restoreDefaults();
                UiUtil.showError(errorLabel, "");
                showCurrent.run();
                showStatus(statusLabel, "Defaults restored.");
            } catch (IOException ex) {
                UiUtil.showError(errorLabel, "Could not save settings: " + ex.getMessage());
            }
        });

        confirmNumbersBtn.setOnAction(e -> {
            UiUtil.clearInvalid(nextQuoteField, nextInvoiceField);
            UiUtil.showError(numbersError, "");
            try {
                long quote = numberField(nextQuoteField, "next quotation number");
                long invoice = numberField(nextInvoiceField, "next invoice number");
                AppDatabase.get().confirmNumbers(quote, invoice);
                showNumbers.run();
                showStatus(statusLabel, "Quotation and invoice numbers confirmed. You can now save quotations and invoices.");
            } catch (InputParser.InvalidInputException ex) {
                UiUtil.showError(numbersError, "Error: " + ex.getMessage());
            } catch (SQLException ex) {
                UiUtil.showError(numbersError, "Could not confirm the numbers: " + ex.getMessage());
            }
        });

        // ---- Layout: one column, centred, scrolling if the window is short
        VBox page = new VBox(14, crumb, heading,
                CalcLayout.info("Changes are saved in the MarginIQ database and remembered the next time you open the app."),
                appearance, textCard, calculations, numbersCard, startup, data, business, backupCard, buttons,
                errorLabel, statusLabel);
        page.setPadding(new Insets(20));
        page.setMaxWidth(760);

        StackPane content = new StackPane(page);
        StackPane.setAlignment(page, Pos.TOP_CENTER);
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("tab-scroll");

        StackPane wrapper = new StackPane(scroll);
        wrapper.setPadding(new Insets(10));
        wrapper.getStyleClass().add(darkTheme ? "dark-theme" : "light-theme");
        return wrapper;
    }

    /** The last number issued for a kind, such as QUO-0011, or "none yet" when nothing has been issued. */
    private static String issuedText(QuoteDocument.Kind kind, long next) {
        return next <= 1 ? "none yet" : MarginDatabase.formatNumber(kind, next - 1);
    }

    /** A whole number from 1 to 999,999,999 for a next-number box. The box is marked when the entry is not valid. */
    private static long numberField(TextField field, String what) throws InputParser.InvalidInputException {
        String text = field.getText() == null ? "" : field.getText().trim();
        long value;
        try {
            value = Long.parseLong(text);
        } catch (NumberFormatException ex) {
            UiUtil.markInvalid(field);
            throw new InputParser.InvalidInputException("Enter the " + what + " as a whole number, such as 12.");
        }
        if (value < 1 || value > 999_999_999L) {
            UiUtil.markInvalid(field);
            throw new InputParser.InvalidInputException("The " + what + " must be between 1 and 999,999,999.");
        }
        return value;
    }

    /** Checks the text in one field. If it is not valid, the field is marked and the message is passed on. */
    private static <T> T check(TextInputControl field, Checker<T> checker) throws InputParser.InvalidInputException {
        try {
            return checker.check(field.getText());
        } catch (InputParser.InvalidInputException ex) {
            UiUtil.markInvalid(field);
            throw ex;
        }
    }

    private static int retentionIndex(int days) {
        for (int i = 0; i < AppSettings.RETENTION_DAYS.length; i++) {
            if (AppSettings.RETENTION_DAYS[i] == days) {
                return i;
            }
        }
        return 0;
    }

    /** A card with a heading, in the same style as the history page's action card. */
    private static VBox card(String title, Node... content) {
        Label heading = new Label(title);
        heading.getStyleClass().add("card-section");
        VBox card = new VBox(12);
        card.getChildren().add(heading);
        card.getChildren().addAll(content);
        card.getStyleClass().add("result-card");
        card.setPadding(new Insets(18));
        return card;
    }

    private static <T extends Region> T fill(T control) {
        control.setMaxWidth(Double.MAX_VALUE);
        return control;
    }

    private static void showStatus(Label label, String text) {
        label.setText(text);
        label.setVisible(!text.isEmpty());
        label.setManaged(!text.isEmpty());
    }
}
