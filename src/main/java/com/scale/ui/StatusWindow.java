package com.scale.ui;

import com.fazecast.jSerialComm.SerialPort;
import com.scale.config.AppConfig;
import com.scale.model.IndicatorType;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;

/**
 * JavaFX operator window.
 * Top section: config inputs (port, indicator type, debug).
 * Bottom section: live scale/client status indicators.
 * All public methods are thread-safe.
 */
public final class StatusWindow {

    public record Selection(String port, IndicatorType indicatorType, boolean debug) {}

    private static final String COL_OK   = "#27AE60";
    private static final String COL_ERR  = "#C0392B";
    private static final String COL_WARN = "#E67E22";
    private static final String COL_IDLE = "#95A5A6";

    private final Label scaleIndicator;
    private final Label scaleValue;
    private final Label clientIndicator;
    private final Label clientValue;
    private final CompletableFuture<Selection> selectionFuture = new CompletableFuture<>();

    /** Must be called on the FX Application Thread. */
    public StatusWindow(AppConfig defaults) {
        // ── Config inputs ─────────────────────────────────────────────────────
        SerialPort[] availablePorts = SerialPort.getCommPorts();

        // Only show port selector when the OS reports actual ports.
        // If none are found, AUTO is used unconditionally.
        ComboBox<PortItem> portCombo = availablePorts.length > 0
                ? buildPortCombo(defaults.portName, availablePorts)
                : null;

        ComboBox<IndicatorType> typeCombo = new ComboBox<>(
                FXCollections.observableArrayList(IndicatorType.values()));
        typeCombo.setValue(defaults.indicatorType);
        typeCombo.setPrefWidth(200);

        CheckBox debugCheck = new CheckBox("Debug rejimi");
        debugCheck.setSelected(defaults.debug);

        GridPane configGrid = new GridPane();
        configGrid.setHgap(12);
        configGrid.setVgap(10);
        int row = 0;
        if (portCombo != null) {
            portCombo.setPrefWidth(200);
            configGrid.addRow(row++, label("Port"), portCombo);
        }
        configGrid.addRow(row++, label("Type"),  typeCombo);
        configGrid.addRow(row,   label(""),      debugCheck);

        // ── Status indicators (assigned before button action captures them) ─────
        scaleIndicator  = indicator();
        scaleValue      = value("Başlanmağı gözləyir...");
        clientIndicator = indicator();
        clientValue     = value("Brauzer oxumur");

        Button startBtn = new Button("Başla");
        startBtn.setDefaultButton(true);
        startBtn.setMaxWidth(Double.MAX_VALUE);

        startBtn.setOnAction(e -> {
            String port = portCombo != null ? portCombo.getValue().portName() : "AUTO";
            selectionFuture.complete(new Selection(port, typeCombo.getValue(), debugCheck.isSelected()));
            if (portCombo != null) portCombo.setDisable(true);
            typeCombo.setDisable(true);
            debugCheck.setDisable(true);
            startBtn.setDisable(true);
            scaleValue.setText("Tərəziyə qoşulur...");
        });

        GridPane statusGrid = new GridPane();
        statusGrid.setHgap(10);
        statusGrid.setVgap(8);
        statusGrid.addRow(0, bold("Tərəzi"),   scaleIndicator,  scaleValue);
        statusGrid.addRow(1, bold("Brauzer"), clientIndicator, clientValue);

        // ── Layout ────────────────────────────────────────────────────────────
        VBox root = new VBox(14, configGrid, startBtn, new Separator(), statusGrid);
        root.setPadding(new Insets(20, 24, 20, 24));
        root.setMinWidth(360);

        Stage stage = new Stage();
        stage.setTitle("Tərəzi Serveri");
        stage.setResizable(false);
        stage.setScene(new Scene(root));
        stage.setOnCloseRequest(e -> System.exit(0));
        stage.show();

        Logger.getLogger(StatusWindow.class.getName()).info("StatusWindow is now visible");
    }

    /** Blocks the calling thread until the user clicks Start. */
    public Selection awaitSelection() {
        try {
            return selectionFuture.get();
        } catch (Exception e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    public void updateScale(boolean connected, String port) {
        Platform.runLater(() -> {
            setColor(scaleIndicator, connected ? COL_OK : COL_ERR);
            scaleValue.setText(connected
                    ? "Tərəziyə qoşuldu — " + port
                    : "Əlaqə kəsildi — serial kabeli yoxlayın");
        });
    }

    public void updateClient(boolean connected) {
        Platform.runLater(() -> {
            setColor(clientIndicator, connected ? COL_OK : COL_WARN);
            clientValue.setText(connected ? "Brauzer oxuyur" : "Brauzer oxumur");
        });
    }

    // ── Port item — separates display label from the port name passed to config ──

    private record PortItem(String portName, String label) {

        static PortItem auto() {
            return new PortItem("AUTO", "AUTO — bütün portları tara");
        }

        static PortItem of(SerialPort p) {
            String sys  = p.getSystemPortName();
            // Linux: sys = "ttyUSB0" — add /dev/ prefix so scale.properties format matches
            String name = (!sys.startsWith("COM") && !sys.startsWith("/")) ? "/dev/" + sys : sys;
            // Strip redundant "(COMx)" suffix Windows appends to descriptive names
            String desc = p.getDescriptivePortName()
                    .replaceAll("\\s*\\([^)]*\\)\\s*$", "").trim();
            String lbl  = (desc.isEmpty() || desc.equalsIgnoreCase(sys))
                    ? name : name + " — " + desc;
            return new PortItem(name, lbl);
        }

        @Override public String toString() { return label; }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static ComboBox<PortItem> buildPortCombo(String current, SerialPort[] ports) {
        var items = FXCollections.<PortItem>observableArrayList(PortItem.auto());
        int selectIdx = 0;
        for (int i = 0; i < ports.length; i++) {
            PortItem item = PortItem.of(ports[i]);
            items.add(item);
            if (item.portName().equals(current)) selectIdx = i + 1;
        }
        var combo = new ComboBox<>(items);
        // Explicit button cell prevents blank display with custom item types
        combo.setButtonCell(new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(PortItem item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.label());
            }
        });
        combo.getSelectionModel().select(selectIdx);
        return combo;
    }

    private static void setColor(Label l, String hex) {
        l.setStyle("-fx-text-fill: " + hex + ";");
    }

    private static Label label(String text) { return new Label(text); }

    private static Label bold(String text) {
        Label l = new Label(text);
        l.setFont(Font.font(null, FontWeight.BOLD, 13));
        return l;
    }

    private static Label indicator() {
        Label l = new Label("●");
        l.setFont(Font.font(16));
        l.setStyle("-fx-text-fill: " + COL_IDLE + ";");
        return l;
    }

    private static Label value(String text) {
        Label l = new Label(text);
        l.setFont(Font.font(13));
        return l;
    }
}
