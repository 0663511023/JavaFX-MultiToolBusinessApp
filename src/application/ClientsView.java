package application;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

/**
 * The Clients page: saved clients with their contact details, VAT choice and usual rates. Also the client
 * picker used on the calculators and in the quote dialog. Documents whose client has been deleted are listed
 * together under Deleted clients, so they can still be opened or removed.
 */
final class ClientsView {

    static final String NONE_TEXT = "No saved client";

    private ClientsView() {
    }

    /** A drop-down of saved clients, with "No saved client" at the top. It follows the saved list as it changes. */
    static ComboBox<ClientProfile> clientPicker() {
        return picker(true);
    }

    /** The same drop-down, fixed to the clients at the time it is made. Used inside the quote dialog. */
    static ComboBox<ClientProfile> snapshotPicker() {
        return picker(false);
    }

    private static ComboBox<ClientProfile> picker(boolean live) {
        ComboBox<ClientProfile> picker = new ComboBox<>();
        picker.setMaxWidth(Double.MAX_VALUE);
        picker.setConverter(new StringConverter<ClientProfile>() {
            @Override
            public String toString(ClientProfile c) {
                return c == null ? NONE_TEXT : c.name;
            }

            @Override
            public ClientProfile fromString(String text) {
                return null;
            }
        });
        Runnable refresh = () -> {
            ClientProfile current = picker.getValue();
            List<ClientProfile> items = new ArrayList<>();
            items.add(null);
            items.addAll(ClientStore.all());
            picker.getItems().setAll(items);
            picker.setValue(sameClient(ClientStore.all(), current));
        };
        refresh.run();
        if (live) {
            ClientStore.addChangeListener(refresh);
        }
        return picker;
    }

    /** The client in the list with the same name as the one given, or null if it has gone. */
    private static ClientProfile sameClient(List<ClientProfile> list, ClientProfile wanted) {
        if (wanted == null) {
            return null;
        }
        for (ClientProfile c : list) {
            if (c.name.equalsIgnoreCase(wanted.name)) {
                return c;
            }
        }
        return null;
    }

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    /** One line for the documents list: number, type, date and total. */
    static String describe(MarginDatabase.DocumentRow row) {
        String type = "INVOICE".equals(row.kind) ? "Invoice" : "Quote";
        return row.number + "   " + type + "   " + row.date.format(DATE) + "   "
                + Money.format("R %,.2f", new BigDecimal(row.total));
    }

    /** A cell for a document row. The deleted-clients list also shows the name the document was issued under. */
    private static ListCell<MarginDatabase.DocumentRow> documentCell(boolean withClientName) {
        return new ListCell<MarginDatabase.DocumentRow>() {
            @Override
            protected void updateItem(MarginDatabase.DocumentRow row, boolean empty) {
                super.updateItem(row, empty);
                setText(empty || row == null ? null
                        : (withClientName ? row.clientName + "   " : "") + describe(row));
            }
        };
    }

    /** Opens the PDF of a document. If it is not where it was saved, the reason is shown instead. */
    private static void openPdf(MarginDatabase.DocumentRow row, Label errorLabel) {
        String path = row.pdfPath;
        java.io.File pdf = path == null || path.isEmpty() ? null : new java.io.File(path);
        if (pdf == null || !pdf.isFile()) {
            UiUtil.showError(errorLabel, "The PDF for " + row.number + " is not where it was saved.");
            return;
        }
        try {
            java.awt.Desktop.getDesktop().open(pdf);
        } catch (IOException | RuntimeException ex) {
            UiUtil.showError(errorLabel, "Could not open the PDF: " + ex.getMessage());
        }
    }

    /**
     * Deletes the chosen documents after asking. The database record always goes. The PDF file is deleted only
     * when the user chooses that too. The list is shown again afterwards, even when something failed.
     */
    private static void deleteDocuments(List<MarginDatabase.DocumentRow> chosen, Node owner, Label errorLabel,
                                        Label statusLabel, Runnable refresh) {
        if (chosen.isEmpty()) {
            UiUtil.showError(errorLabel, "Select one or more documents first.");
            return;
        }

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Delete documents");
        alert.setHeaderText("Delete " + chosen.size() + (chosen.size() == 1 ? " document?" : " documents?"));
        alert.setContentText("The documents are removed from the list, and their numbers are not used "
                + "again. Their PDF files on your computer are kept, unless you choose to remove them too.");
        if (owner != null && owner.getScene() != null) {
            alert.initOwner(owner.getScene().getWindow());
        }
        ButtonType onlyList = new ButtonType("Delete from list");
        ButtonType withPdf = new ButtonType("Delete and remove PDF");
        alert.getButtonTypes().setAll(onlyList, withPdf, ButtonType.CANCEL);
        Optional<ButtonType> answer = alert.showAndWait();
        if (answer.isEmpty() || answer.get() == ButtonType.CANCEL) {
            return;
        }
        boolean removePdf = answer.get() == withPdf;

        int removed = 0;
        try {
            for (MarginDatabase.DocumentRow row : chosen) {
                AppDatabase.get().deleteDocument(row.id);
                removed++;
                if (removePdf && row.pdfPath != null && !row.pdfPath.isEmpty()) {
                    Files.deleteIfExists(Paths.get(row.pdfPath));
                }
            }
            statusLabel.setText("Deleted " + removed + (removed == 1 ? " document." : " documents."));
        } catch (java.sql.SQLException | IOException | RuntimeException ex) {
            UiUtil.showError(errorLabel, "Could not delete all the documents: " + ex.getMessage());
        } finally {
            refresh.run();
        }
    }

    static VBox createLayout(boolean darkTheme) {
        Label errorLabel = UiUtil.createErrorLabel();
        Label statusLabel = CalcLayout.info("");
        boolean vatRegistered = Branding.current().hasVatNumber();

        ListView<ClientProfile> list = new ListView<>();
        list.setPrefHeight(340);
        list.setPlaceholder(new Label("No saved clients yet. Fill in the form and press Save client."));
        list.getItems().setAll(ClientStore.all());

        // The quotations and invoices issued to the selected client. Double-click one to open its PDF.
        ListView<MarginDatabase.DocumentRow> docsList = new ListView<>();
        docsList.setPrefHeight(200);
        docsList.setPlaceholder(new Label("Select a saved client to see the quotes and invoices issued to them."));
        docsList.setCellFactory(lv -> documentCell(false));
        // Several documents can be ticked at once, so more than one can be deleted together
        docsList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        // The documents of deleted clients, and of documents typed in without a saved client
        ListView<MarginDatabase.DocumentRow> deletedList = new ListView<>();
        deletedList.setPrefHeight(200);
        deletedList.setPlaceholder(new Label("No documents belong to deleted clients."));
        deletedList.setCellFactory(lv -> documentCell(true));
        deletedList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        Runnable showDocuments = () -> {
            int index = list.getSelectionModel().getSelectedIndex();
            docsList.getItems().clear();
            if (index < 0 || index >= ClientStore.all().size()) {
                return;
            }
            try {
                docsList.getItems().setAll(ClientStore.documentsFor(index));
            } catch (java.sql.SQLException ex) {
                UiUtil.showError(errorLabel, "Could not read the documents: " + ex.getMessage());
            }
        };
        Runnable showDeleted = () -> {
            try {
                deletedList.getItems().setAll(ClientStore.documentsWithoutClient());
            } catch (java.sql.SQLException ex) {
                UiUtil.showError(errorLabel, "Could not read the documents of deleted clients: " + ex.getMessage());
            }
        };
        Runnable showAllDocuments = () -> {
            showDocuments.run();
            showDeleted.run();
        };

        Runnable openSelectedPdf = () -> {
            MarginDatabase.DocumentRow row = docsList.getSelectionModel().getSelectedItem();
            if (row == null) {
                UiUtil.showError(errorLabel, "Select a document first.");
                return;
            }
            openPdf(row, errorLabel);
        };
        docsList.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                openSelectedPdf.run();
            }
        });
        Button openPdfBtn = new Button("Open PDF");
        openPdfBtn.setOnAction(e -> openSelectedPdf.run());
        Button deleteDocsBtn = new Button("Delete Selected");
        deleteDocsBtn.getStyleClass().add("button-danger");
        deleteDocsBtn.setOnAction(e -> deleteDocuments(
                new ArrayList<>(docsList.getSelectionModel().getSelectedItems()), deleteDocsBtn,
                errorLabel, statusLabel, showAllDocuments));
        HBox docsButtons = new HBox(10, openPdfBtn, deleteDocsBtn);
        Label docsHeading = new Label("QUOTES & INVOICES FOR THIS CLIENT");
        docsHeading.getStyleClass().add("card-section");
        Label docsHint = CalcLayout.info("Double-click a document, or select it and press Open PDF. "
                + "Select several and press Delete Selected to remove them from the client's list.");
        VBox docsCard = new VBox(10, docsHeading, docsList, docsButtons, docsHint);
        docsCard.getStyleClass().add("result-card");
        docsCard.setPadding(new Insets(18));

        deletedList.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                MarginDatabase.DocumentRow row = deletedList.getSelectionModel().getSelectedItem();
                if (row != null) {
                    openPdf(row, errorLabel);
                }
            }
        });
        Button openDeletedBtn = new Button("Open PDF");
        openDeletedBtn.setOnAction(e -> {
            MarginDatabase.DocumentRow row = deletedList.getSelectionModel().getSelectedItem();
            if (row == null) {
                UiUtil.showError(errorLabel, "Select a document first.");
                return;
            }
            openPdf(row, errorLabel);
        });
        Button deleteDeletedBtn = new Button("Delete Selected");
        deleteDeletedBtn.getStyleClass().add("button-danger");
        deleteDeletedBtn.setOnAction(e -> deleteDocuments(
                new ArrayList<>(deletedList.getSelectionModel().getSelectedItems()), deleteDeletedBtn,
                errorLabel, statusLabel, showAllDocuments));
        HBox deletedButtons = new HBox(10, openDeletedBtn, deleteDeletedBtn);
        Label deletedHeading = new Label("DELETED CLIENTS");
        deletedHeading.getStyleClass().add("card-section");
        Label deletedHint = CalcLayout.info("Quotes and invoices whose client has been deleted, or that were typed in "
                + "without a saved client, are grouped here by the name they were issued under.");
        VBox deletedCard = new VBox(10, deletedHeading, deletedList, deletedButtons, deletedHint);
        deletedCard.getStyleClass().add("result-card");
        deletedCard.setPadding(new Insets(18));

        TextField nameField = new TextField();
        nameField.setPromptText("e.g. Sipho Mokoena or a company name");
        TextField streetField = new TextField();
        streetField.setPromptText("e.g. 8 Jan Smuts Avenue");
        TextField suburbField = new TextField();
        suburbField.setPromptText("e.g. Rosebank or Sandton");
        TextField postalField = new TextField();
        postalField.setPromptText("e.g. 2196");
        TextField phoneField = new TextField();
        phoneField.setPromptText("e.g. 082 123 4567");
        TextField emailField = new TextField();
        emailField.setPromptText("e.g. name@example.co.za");
        TextField vatField = new TextField();
        vatField.setPromptText("10 digits (optional)");
        CheckBox chargesVat = new CheckBox("Charge VAT on this client's quotes and invoices");
        chargesVat.setDisable(!vatRegistered);
        Label vatNote = CalcLayout.info("VAT can only be charged once your VAT number is set in Settings.");
        vatNote.setVisible(!vatRegistered);
        vatNote.setManaged(!vatRegistered);
        TextField rateField = new TextField();
        rateField.setPromptText("e.g. 450 per hour (optional)");
        TextField markupField = new TextField();
        markupField.setPromptText("e.g. 40 for 40% (optional)");
        FieldCheck.contactInput(phoneField, emailField, postalField, vatField);

        Button saveBtn = new Button("Save client");
        saveBtn.getStyleClass().add("button-primary");
        Button newBtn = new Button("New client");
        newBtn.getStyleClass().add("pill-button");
        Button deleteBtn = new Button("Delete client");
        deleteBtn.getStyleClass().add("button-danger");

        // The index of the client being edited in the saved list, or -1 for a new client
        final int[] editing = {-1};

        Runnable clearForm = () -> {
            editing[0] = -1;
            for (TextField f : List.of(nameField, streetField, suburbField, postalField, phoneField,
                    emailField, vatField, rateField, markupField)) {
                f.clear();
            }
            chargesVat.setSelected(false);
            UiUtil.clearInvalid(nameField, streetField, suburbField, postalField, phoneField, emailField,
                    vatField, rateField, markupField);
            list.getSelectionModel().clearSelection();
        };

        list.getSelectionModel().selectedIndexProperty().addListener((obs, a, index) -> {
            showDocuments.run();
            if (index.intValue() < 0 || index.intValue() >= ClientStore.all().size()) {
                return;
            }
            ClientProfile c = ClientStore.all().get(index.intValue());
            editing[0] = index.intValue();
            nameField.setText(c.name);
            streetField.setText(c.contact.street);
            suburbField.setText(c.contact.suburb);
            postalField.setText(c.contact.postalCode);
            phoneField.setText(ContactDetails.formatPhone(c.contact.phone));
            emailField.setText(c.contact.email);
            vatField.setText(c.contact.vatNumber);
            chargesVat.setSelected(vatRegistered && c.chargesVat);
            rateField.setText(c.hourlyRate == null ? "" : c.hourlyRate.stripTrailingZeros().toPlainString());
            markupField.setText(c.markupPercent == null ? "" : c.markupPercent.stripTrailingZeros().toPlainString());
            UiUtil.clearInvalid(nameField, streetField, suburbField, postalField, phoneField, emailField,
                    vatField, rateField, markupField);
            UiUtil.showError(errorLabel, "");
            statusLabel.setText("");
        });
        // Saving or deleting anywhere refreshes the list; the selection is put back by the save itself
        ClientStore.addChangeListener(() -> {
            list.getItems().setAll(ClientStore.all());
            showAllDocuments.run();
        });
        showDeleted.run();

        saveBtn.setOnAction(e -> {
            UiUtil.clearInvalid(nameField, streetField, suburbField, postalField, phoneField, emailField,
                    vatField, rateField, markupField);
            UiUtil.showError(errorLabel, "");
            statusLabel.setText("");
            try {
                String name = FieldCheck.check(nameField, ClientProfile::checkName);
                String street = FieldCheck.check(streetField, ContactDetails::checkStreet);
                String suburb = FieldCheck.check(suburbField, ContactDetails::checkSuburb);
                String postal = FieldCheck.check(postalField, ContactDetails::checkPostalCode);
                String phone = FieldCheck.check(phoneField, ContactDetails::checkPhone);
                String email = FieldCheck.check(emailField, ContactDetails::checkEmail);
                String vat = FieldCheck.check(vatField, ContactDetails::checkVatNumber);
                BigDecimal rate = FieldCheck.check(rateField, ClientProfile::checkRate);
                BigDecimal markup = FieldCheck.check(markupField, ClientProfile::checkMarkup);

                List<ClientProfile> next = new ArrayList<>(ClientStore.all());
                int index = editing[0];
                ClientStore.checkUnique(next, name, index);
                ClientProfile profile = new ClientProfile(name,
                        new ContactDetails(street, suburb, postal, phone, email, vat),
                        vatRegistered && chargesVat.isSelected(), rate, markup);
                if (index < 0) {
                    ClientStore.add(profile);
                    index = ClientStore.all().size() - 1;
                } else {
                    ClientStore.update(index, profile);
                }
                list.getSelectionModel().select(index);
                statusLabel.setText(name + " saved.");
            } catch (InputParser.InvalidInputException ex) {
                UiUtil.showError(errorLabel, "Error: " + ex.getMessage());
            } catch (java.sql.SQLException ex) {
                UiUtil.showError(errorLabel, "Could not save the client: " + ex.getMessage());
            }
        });

        newBtn.setOnAction(e -> {
            clearForm.run();
            UiUtil.showError(errorLabel, "");
            statusLabel.setText("");
            nameField.requestFocus();
        });

        deleteBtn.setOnAction(e -> {
            int index = editing[0];
            if (index < 0 || index >= ClientStore.all().size()) {
                UiUtil.showError(errorLabel, "Select a saved client in the list first.");
                return;
            }
            ClientProfile c = ClientStore.all().get(index);
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "Delete " + c.name + "? Their saved details and "
                    + "rates will be removed. Quotes and invoices already saved as PDF are not changed, and are "
                    + "listed under Deleted clients.");
            confirm.setHeaderText(null);
            confirm.initOwner(deleteBtn.getScene().getWindow());
            if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                return;
            }
            try {
                ClientStore.delete(index);
                clearForm.run();
                UiUtil.showError(errorLabel, "");
                statusLabel.setText(c.name + " deleted.");
            } catch (java.sql.SQLException ex) {
                UiUtil.showError(errorLabel, "Could not delete the client: " + ex.getMessage());
            }
        });

        VBox inputs = CalcLayout.inputsCard(
                CalcLayout.field(CalcLayout.label("Client name:"), nameField),
                CalcLayout.field(CalcLayout.label("Street address:"), streetField),
                CalcLayout.field(CalcLayout.label("Suburb / city:"), suburbField),
                CalcLayout.field(CalcLayout.label("Postal code:"), postalField),
                CalcLayout.field(CalcLayout.label("Phone:"), phoneField),
                CalcLayout.field(CalcLayout.label("Email:"), emailField),
                CalcLayout.field(CalcLayout.label("Client VAT no.:"), vatField),
                chargesVat,
                vatNote,
                CalcLayout.field(CalcLayout.label("Default hourly rate (R, before VAT):"), rateField),
                CalcLayout.field(CalcLayout.label("Preferred markup (%):"), markupField),
                new HBox(10, saveBtn, newBtn, deleteBtn));

        Label heading = new Label("SAVED CLIENTS");
        heading.getStyleClass().add("card-section");
        VBox results = new VBox(10, heading, list, statusLabel);
        results.getStyleClass().add("result-card");
        results.setPadding(new Insets(18));

        return CalcLayout.page("Clients", "Client Profiles", errorLabel,
                CalcLayout.info("Saved clients fill in the quote and invoice dialog. Margin & Markup uses a client's "
                        + "preferred markup, and Freelance Hourly Rate checks their default rate against your minimum."),
                inputs, new VBox(16, results, docsCard, deletedCard));
    }
}
