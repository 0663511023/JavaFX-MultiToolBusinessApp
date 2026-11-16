package application;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.List;

import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.FileChooser;
import javafx.stage.Screen;
import javafx.stage.Window;

/**
 * Turns a calculation into a client-ready quotation or tax invoice, saved as a branded PDF.
 * The document type is a choice of one (radio buttons). VAT can only be charged once a VAT number is set in
 * Settings. A quote from the Freelance Hourly Rate tab locks its rate and hours to the calculation, so the
 * document always matches it. The quote or invoice number only moves on once the PDF has been saved.
 */
final class QuoteDialog {

    private static final BigDecimal MAX_QUANTITY = new BigDecimal("1000000");

    /** What the document charges for. A locked set of terms comes from a calculator and cannot be edited. */
    static final class Terms {
        final String description;
        final BigDecimal unitPrice;
        final BigDecimal quantity;
        final String unitLabel;
        final String quantityLabel;
        final boolean locked;
        final String note;

        private Terms(String description, BigDecimal unitPrice, BigDecimal quantity, String unitLabel,
                      String quantityLabel, boolean locked, String note) {
            this.description = description;
            this.unitPrice = unitPrice;
            this.quantity = quantity;
            this.unitLabel = unitLabel;
            this.quantityLabel = quantityLabel;
            this.locked = locked;
            this.note = note;
        }

        /** A quote whose price and quantity can be changed in the dialog (Margin & Markup). */
        static Terms free(String description, BigDecimal unitPrice) {
            return new Terms(description, unitPrice, BigDecimal.ONE, "Unit price (R)", "Quantity", false, null);
        }

        /** A quote from the Freelance Hourly Rate tab: the rate and the billable hours are locked. */
        static Terms hourly(String description, BigDecimal ratePerHour, BigDecimal hoursPerWeek) {
            return new Terms(description, ratePerHour, hoursPerWeek, "Rate (R/hr)", "Billable hours per week", true,
                    "The rate and hours come from the calculator and are locked, so the quote matches it.");
        }
    }

    private QuoteDialog() {}

    /** Every control in the dialog, with the logic that keeps them consistent. */
    private static final class Form {
        final Terms terms;
        final boolean registered = Branding.current().hasVatNumber();

        final ToggleGroup kindGroup = new ToggleGroup();
        final RadioButton quotation = new RadioButton("Quotation");
        final RadioButton invoice = new RadioButton("Invoice");
        final Label number = new Label();
        final ComboBox<ClientProfile> clientChoice = ClientsView.snapshotPicker();
        final TextField clientName = new TextField();
        final TextField street = new TextField();
        final TextField suburb = new TextField();
        final TextField postal = new TextField();
        final TextField phone = new TextField();
        final TextField email = new TextField();
        final TextField clientVat = new TextField();
        final TextField description = new TextField();
        final TextField quantity = new TextField();
        final TextField unitPrice = new TextField();
        final CheckBox charge = new CheckBox();
        final CheckBox included = new CheckBox("The price already includes VAT");
        final DatePicker validUntil = new DatePicker(LocalDate.now().plusDays(30));
        final TextArea notes = new TextArea();
        final Label errorLabel = UiUtil.createErrorLabel();

        Form(Terms terms) {
            this.terms = terms;
            quotation.setToggleGroup(kindGroup);
            invoice.setToggleGroup(kindGroup);
            quotation.setSelected(true);

            clientName.setPromptText("e.g. Sipho Mokoena or a company name");
            street.setPromptText("e.g. 8 Jan Smuts Avenue");
            suburb.setPromptText("e.g. Rosebank or Sandton");
            postal.setPromptText("e.g. 2196");
            phone.setPromptText("e.g. 082 123 4567");
            email.setPromptText("e.g. name@example.co.za");
            clientVat.setPromptText("10 digits (optional)");
            FieldCheck.contactInput(phone, email, postal, clientVat);

            description.setText(terms.description);
            quantity.setText(Money.machine(terms.quantity));
            unitPrice.setText(Money.machine(terms.unitPrice));
            if (terms.locked) {
                // Locked: shown for the client to see, but they cannot be changed here
                for (TextField field : List.of(quantity, unitPrice)) {
                    field.setEditable(false);
                    field.getStyleClass().add("read-only");
                }
            }

            charge.setText("Charge VAT at " + AppSettings.vatPercentText() + "%");
            validUntil.setMaxWidth(Double.MAX_VALUE);
            notes.setPromptText("Payment terms or banking details (optional)");
            notes.setPrefRowCount(3);

            kindGroup.selectedToggleProperty().addListener((obs, a, b) ->
                    number.setText(nextNumberText(selectedKind())));
            number.setText(nextNumberText(selectedKind()));

            charge.setSelected(registered);
            charge.selectedProperty().addListener((obs, a, b) -> refreshVat());
            refreshVat();
        }

        QuoteDocument.Kind selectedKind() {
            return invoice.isSelected() ? QuoteDocument.Kind.INVOICE : QuoteDocument.Kind.QUOTE;
        }

        /**
         * VAT controls follow the VAT number. Without one they are switched off, so a document cannot claim VAT
         * that the business is not registered for. "Price includes VAT" only applies when VAT is charged, and
         * never to a locked calculation.
         */
        void refreshVat() {
            charge.setDisable(!registered);
            if (!registered) {
                charge.setSelected(false);
            }
            boolean includedAllowed = registered && charge.isSelected() && !terms.locked;
            included.setDisable(!includedAllowed);
            if (!includedAllowed) {
                included.setSelected(false);
            }
        }

        /** Copies a saved client's details into the form. Only the client's own fields change. */
        void fillFrom(ClientProfile c) {
            if (c == null) {
                return;
            }
            clientName.setText(c.name);
            street.setText(c.contact.street);
            suburb.setText(c.contact.suburb);
            postal.setText(c.contact.postalCode);
            phone.setText(ContactDetails.formatPhone(c.contact.phone));
            email.setText(c.contact.email);
            clientVat.setText(c.contact.vatNumber);
            charge.setSelected(registered && c.chargesVat);
            refreshVat();
        }

        void clearMarks() {
            UiUtil.clearInvalid(clientName, street, suburb, postal, phone, email, clientVat, description,
                    quantity, unitPrice, validUntil);
        }
    }

    /**
     * Opens the dialog. preselected is the client the calculator has chosen, or null. Its details fill the
     * form, and the user can pick another client in the dialog.
     */
    static void open(Window owner, Terms terms, ClientProfile preselected) {
        Form f = new Form(terms);

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));
        grid.getColumnConstraints().add(new ColumnConstraints(160));
        grid.getColumnConstraints().add(new ColumnConstraints(320));
        int row = 0;
        grid.add(CalcLayout.info("The document shows only the client's details, the description, quantity, unit "
                + "price, VAT and total. Your cost, margin, markup and tax workings are never printed."), 0, row++, 2, 1);
        if (!f.registered) {
            grid.add(CalcLayout.info("VAT is off because no VAT number is set. Add your VAT number in Settings "
                    + "to charge VAT."), 0, row++, 2, 1);
        }
        HBox kindRow = new HBox(18, f.quotation, f.invoice);
        row = addRow(grid, row, "Saved client:", f.clientChoice);
        row = addRow(grid, row, "Document:", kindRow);
        row = addRow(grid, row, "Number:", f.number);
        row = addRow(grid, row, "Client name:", f.clientName);
        row = addRow(grid, row, "Street address:", f.street);
        row = addRow(grid, row, "Suburb / city:", f.suburb);
        row = addRow(grid, row, "Postal code:", f.postal);
        row = addRow(grid, row, "Phone:", f.phone);
        row = addRow(grid, row, "Email:", f.email);
        row = addRow(grid, row, "Client VAT no.:", f.clientVat);
        row = addRow(grid, row, "Description:", f.description);
        row = addRow(grid, row, terms.quantityLabel + ":", f.quantity);
        row = addRow(grid, row, terms.unitLabel + ":", f.unitPrice);
        if (terms.note != null) {
            grid.add(CalcLayout.info(terms.note), 1, row++);
        }
        grid.add(f.charge, 1, row++);
        grid.add(f.included, 1, row++);
        row = addRow(grid, row, "Valid until / due date:", f.validUntil);
        row = addRow(grid, row, "Notes:", f.notes);
        grid.add(f.errorLabel, 0, row, 2, 1);

        // The saved client fills the form. Picking another one in the dialog fills it again.
        if (preselected != null) {
            f.clientChoice.setValue(preselected);
            f.fillFrom(preselected);
        }
        f.clientChoice.valueProperty().addListener((obs, a, c) -> f.fillFrom(c));

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Create a quotation or invoice");
        // The form scrolls inside a height the screen can show, so Save and Cancel always stay on screen,
        // even on a short laptop display. The dialog can also be resized.
        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("tab-scroll");
        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        double bodyHeight = Math.max(300, Math.min(760, screen.getHeight() - 200));
        scroll.setPrefHeight(bodyHeight);
        scroll.setMaxHeight(bodyHeight);
        dialog.getDialogPane().setContent(scroll);
        dialog.setResizable(true);
        // A dialog is a separate window, so it needs the app's styles and theme too
        java.net.URL css = QuoteDialog.class.getResource("application.css");
        if (css != null) {
            dialog.getDialogPane().getStylesheets().add(css.toExternalForm());
        }
        // The text size and typeface apply to the dialog too
        String textCss = FontStyle.currentUrl();
        if (textCss != null) {
            dialog.getDialogPane().getStylesheets().add(textCss);
        }
        dialog.getDialogPane().getStyleClass().add(AppSettings.isDarkTheme() ? "dark-theme" : "light-theme");

        ButtonType saveType = new ButtonType("Save PDF", ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);
        Button saveButton = (Button) dialog.getDialogPane().lookupButton(saveType);
        // Stay open when something needs fixing; close once the PDF is saved
        saveButton.addEventFilter(ActionEvent.ACTION, event -> {
            if (!save(owner, f)) {
                event.consume();
            }
        });
        dialog.showAndWait();
    }

    private static int addRow(GridPane grid, int row, String label, Node control) {
        grid.add(CalcLayout.label(label), 0, row);
        grid.add(control, 1, row);
        GridPane.setHgrow(control, Priority.ALWAYS);
        return row + 1;
    }

    /** The number the next document of this kind will get. A database problem is shown instead of a number. */
    static String nextNumberText(QuoteDocument.Kind kind) {
        try {
            return AppDatabase.peekNumber(kind);
        } catch (java.sql.SQLException ex) {
            return "Unavailable";
        }
    }

    /** Checks every field, builds the document, asks where to save it and writes the PDF. True when saved. */
    private static boolean save(Window owner, Form f) {
        f.clearMarks();
        try {
            String name = FieldCheck.check(f.clientName, ClientProfile::checkName);
            String street = FieldCheck.check(f.street, ContactDetails::checkStreet);
            String suburb = FieldCheck.check(f.suburb, ContactDetails::checkSuburb);
            String postal = FieldCheck.check(f.postal, ContactDetails::checkPostalCode);
            String phone = FieldCheck.check(f.phone, ContactDetails::checkPhone);
            String email = FieldCheck.check(f.email, ContactDetails::checkEmail);
            String clientVat = FieldCheck.check(f.clientVat, ContactDetails::checkVatNumber);
            String what = FieldCheck.check(f.description, text -> {
                String value = text.trim();
                if (value.isEmpty()) {
                    throw new InputParser.InvalidInputException("Enter a description for the item.");
                }
                return value;
            });

            // A locked calculation always uses its own figures, whatever the boxes show
            BigDecimal amount = f.terms.locked ? f.terms.quantity
                    : FieldCheck.check(f.quantity, text -> InputParser.decimalInRange(text, "Quantity",
                            BigDecimal.ZERO, MAX_QUANTITY));
            BigDecimal unit = f.terms.locked ? f.terms.unitPrice
                    : FieldCheck.check(f.unitPrice, text -> InputParser.decimalAtLeast(text, "Unit price",
                            BigDecimal.ZERO));

            if (f.validUntil.getValue() == null) {
                UiUtil.markInvalid(f.validUntil);
                throw new InputParser.InvalidInputException("Choose the date the document is valid until.");
            }
            if (f.charge.isSelected() && !f.registered) {
                throw new InputParser.InvalidInputException(
                        "Add your VAT number in Settings before charging VAT, so the document can show it.");
            }
            boolean charging = f.charge.isSelected();
            BigDecimal vatRate = charging ? AppSettings.vatRate() : BigDecimal.ZERO;
            if (charging && f.included.isSelected() && !f.terms.locked) {
                unit = QuoteDocument.exclusiveFrom(unit, vatRate);
            }

            QuoteDocument.Kind kind = f.selectedKind();
            if (!AppDatabase.get().numbersConfirmed()) {
                // Refused before anything is written: the numbers may be behind those already issued
                Alert refused = new Alert(Alert.AlertType.WARNING,
                        "The quotation and invoice numbers have not been confirmed yet, so nothing can be saved. "
                        + "Open Settings, and enter the next quotation and invoice numbers in the "
                        + "Quote & Invoice Numbers section.");
                refused.setHeaderText(null);
                refused.initOwner(owner);
                refused.showAndWait();
                UiUtil.showError(f.errorLabel, "Confirm the quotation and invoice numbers in Settings first.");
                return false;
            }
            Branding branding = Branding.current();
            ContactDetails contact = new ContactDetails(street, suburb, postal, phone, email, clientVat);
            QuoteDocument document = new QuoteDocument(kind, AppDatabase.peekNumber(kind), LocalDate.now(),
                    f.validUntil.getValue(), name, contact,
                    List.of(new QuoteDocument.Item(what, amount, unit)), vatRate, f.notes.getText());

            FileChooser chooser = new FileChooser();
            chooser.setTitle("Save the " + (kind == QuoteDocument.Kind.QUOTE ? "quotation" : "invoice") + " as PDF");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF document", "*.pdf"));
            chooser.setInitialFileName(document.number + "-" + safeName(name) + ".pdf");
            String folder = AppSettings.exportFolder();
            chooser.setInitialDirectory(new File(folder.isEmpty() ? System.getProperty("user.home") : folder));
            File file = chooser.showSaveDialog(owner);
            if (file == null) {
                return false; // cancelled: the dialog stays open, and nothing is used up
            }

            // The PDF and its record are written as one step, so the number is used only when both succeed
            Long clientId = ClientStore.idOf(f.clientChoice.getValue());
            AppDatabase.get().issueDocument(document, clientId, file.toPath(),
                    () -> Files.write(file.toPath(), QuotePdf.build(document, branding).toBytes()));
            ClientStore.announceChange();
            UiUtil.showError(f.errorLabel, "");
            Alert done = new Alert(Alert.AlertType.INFORMATION,
                    document.number + " was saved to " + file.getAbsolutePath() + ".");
            done.setHeaderText(null);
            done.initOwner(owner);
            done.showAndWait();
            return true;
        } catch (InputParser.InvalidInputException ex) {
            UiUtil.showError(f.errorLabel, "Error: " + ex.getMessage());
            return false;
        } catch (IOException | java.sql.SQLException | RuntimeException ex) {
            UiUtil.showError(f.errorLabel, "Could not save the PDF: " + ex.getMessage());
            return false;
        }
    }

    /** A file name made of letters, digits, spaces and dashes only. */
    static String safeName(String name) {
        String cleaned = name.replaceAll("[^A-Za-z0-9 \\-]", "").trim().replaceAll("\\s+", "_");
        return cleaned.isEmpty() ? "Client" : cleaned;
    }
}
