package dev.claudev.ui;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.awt.AWTException;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;

/**
 * The JavaFX half of the single-JVM lifecycle (D6, D15): {@code app-bootstrap} starts the Spring
 * context first, then launches this {@link Application} against the already-running context.
 *
 * <p>Two tabs: a diagnostics view (live SQLite pragmas, a real DPAPI round trip, a real Job Object
 * creation, compiled-in adapter manifests) and, as of WP5, a workspace view that lists/creates/
 * deletes workspaces and dummy instances and starts/stops them as real operations against a real
 * spawned process — see {@link WorkspacesPane} and docs/MILESTONES.md WP5.
 */
public class ClaudevShell extends Application {

    private static volatile Runnable onQuitRequested = () -> { };
    private static volatile DiagnosticsSource diagnosticsSource = DiagnosticsSource.unavailable();
    private static volatile WorkspaceControlPort workspaceControlPort = WorkspaceControlPort.unavailable();
    private static volatile ConnectionControlPort connectionControlPort = ConnectionControlPort.unavailable();
    private static volatile ClaudevShell activeInstance;

    private final VBox sections = new VBox(18);
    private WorkspacesPane workspacesPane;
    private ConnectionsPane connectionsPane;
    private Stage primaryStage;
    private TrayIcon trayIcon;

    /** app-bootstrap wires this to "run the operation engine's graceful Stop All, then settle." */
    public static void setOnQuitRequested(Runnable handler) {
        onQuitRequested = handler == null ? () -> { } : handler;
    }

    public static void setDiagnosticsSource(DiagnosticsSource source) {
        diagnosticsSource = source == null ? DiagnosticsSource.unavailable() : source;
    }

    public static void setWorkspaceControlPort(WorkspaceControlPort port) {
        workspaceControlPort = port == null ? WorkspaceControlPort.unavailable() : port;
    }

    public static void setConnectionControlPort(ConnectionControlPort port) {
        connectionControlPort = port == null ? ConnectionControlPort.unavailable() : port;
    }

    @Override
    public void start(Stage primaryStage) {
        this.primaryStage = primaryStage;
        activeInstance = this;
        Platform.setImplicitExit(false);

        Label title = new Label("claudev");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        Label subtitle = new Label("dev workspace orchestrator");
        subtitle.setStyle("-fx-text-fill: #666;");

        Button refresh = new Button("Refresh diagnostics");
        refresh.setOnAction(event -> render());

        Button quit = new Button("Quit");
        quit.setOnAction(event -> quit());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox header = new HBox(12, new VBox(2, title, subtitle), spacer, refresh, quit);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(16, 20, 16, 20));
        header.setStyle("-fx-background-color: #f0f0f2; -fx-border-color: transparent transparent #d8d8dc transparent; -fx-border-width: 0 0 1 0;");

        sections.setPadding(new Insets(20));
        render();

        workspacesPane = new WorkspacesPane(workspaceControlPort);
        connectionsPane = new ConnectionsPane(connectionControlPort);

        TabPane tabs = new TabPane(
                new Tab("Workspaces (RabbitMQ)", workspacesPane),
                new Tab("Redis Connections", connectionsPane),
                new Tab("Diagnostics", sections));
        tabs.getTabs().forEach(tab -> tab.setClosable(false));

        VBox root = new VBox(header, tabs);
        VBox.setVgrow(tabs, Priority.ALWAYS);

        primaryStage.setTitle("claudev");
        primaryStage.setScene(new Scene(root, 1000, 680));

        setUpTray(primaryStage);

        primaryStage.setOnCloseRequest(event -> {
            event.consume();
            if (trayIcon != null) {
                primaryStage.hide();
            } else {
                // No tray icon available on this platform/session — hiding would strand an
                // invisible process with no way back (docs/PROCESS_SAFETY.md), so quit instead.
                quit();
            }
        });
        primaryStage.show();
    }

    /**
     * Best-effort: {@link SystemTray#isSupported()} is false on some CI/RDP sessions, in which case
     * the window-close handler above falls back to a real quit rather than pretending tray/minimize
     * works.
     */
    private void setUpTray(Stage stage) {
        if (!SystemTray.isSupported()) {
            return;
        }
        try {
            PopupMenu menu = new PopupMenu();
            MenuItem show = new MenuItem("Show claudev");
            show.addActionListener(e -> Platform.runLater(() -> {
                stage.show();
                stage.toFront();
            }));
            MenuItem quitItem = new MenuItem("Quit");
            quitItem.addActionListener(e -> Platform.runLater(ClaudevShell::quit));
            menu.add(show);
            menu.add(quitItem);

            trayIcon = new TrayIcon(trayImage(), "claudev", menu);
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(e -> Platform.runLater(() -> {
                stage.show();
                stage.toFront();
            }));
            SystemTray.getSystemTray().add(trayIcon);
        } catch (AWTException e) {
            trayIcon = null;
        }
    }

    /** A minimal generated icon — no bundled asset exists yet (see docs/MILESTONES.md WP9 packaging). */
    private static Image trayImage() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        var g = image.createGraphics();
        g.setColor(new java.awt.Color(0x2f6fed));
        g.fillOval(1, 1, 14, 14);
        g.setColor(java.awt.Color.WHITE);
        g.drawString("c", 5, 12);
        g.dispose();
        return image;
    }

    private void render() {
        DiagnosticsSnapshot snapshot = diagnosticsSource.snapshot();
        sections.getChildren().setAll(
                section("Runtime", runtimeGrid(snapshot)),
                section("Persistence (live SQLite read)", persistenceGrid(snapshot)),
                section("Live checks", checksGrid(snapshot)),
                section("Compiled-in adapters", adapterTable(snapshot)));
    }

    private VBox section(String heading, Region body) {
        Label label = new Label(heading);
        label.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #333;");
        return new VBox(8, label, body);
    }

    private GridPane runtimeGrid(DiagnosticsSnapshot snapshot) {
        GridPane grid = grid();
        addRow(grid, 0, "Java", snapshot.javaVersion());
        addRow(grid, 1, "JavaFX", snapshot.javafxVersion());
        addRow(grid, 2, "OS", snapshot.os());
        addRow(grid, 3, "User", snapshot.user());
        return grid;
    }

    private GridPane persistenceGrid(DiagnosticsSnapshot snapshot) {
        GridPane grid = grid();
        addRow(grid, 0, "Database", snapshot.dbPath());
        addRow(grid, 1, "journal_mode", snapshot.journalMode());
        addRow(grid, 2, "busy_timeout", snapshot.busyTimeout());
        return grid;
    }

    private GridPane checksGrid(DiagnosticsSnapshot snapshot) {
        GridPane grid = grid();
        addCheck(grid, 0, "SecretStore (DPAPI)", snapshot.secretStore());
        addCheck(grid, 1, "Windows Job Object", snapshot.jobObject());
        addCheck(grid, 2, "Reconciler dry run", snapshot.reconciler());
        addCheck(grid, 3, "Operation engine self-test", snapshot.operationEngine());
        return grid;
    }

    private TableView<DiagnosticsSnapshot.AdapterRow> adapterTable(DiagnosticsSnapshot snapshot) {
        TableView<DiagnosticsSnapshot.AdapterRow> table =
                new TableView<>(FXCollections.observableArrayList(snapshot.adapters()));
        table.setPrefHeight(200);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getColumns().addAll(
                column("Adapter", DiagnosticsSnapshot.AdapterRow::id),
                column("Version", DiagnosticsSnapshot.AdapterRow::version),
                column("Capabilities", DiagnosticsSnapshot.AdapterRow::capabilities),
                column("Status", DiagnosticsSnapshot.AdapterRow::status));
        return table;
    }

    private TableColumn<DiagnosticsSnapshot.AdapterRow, String> column(
            String heading, java.util.function.Function<DiagnosticsSnapshot.AdapterRow, String> value) {
        TableColumn<DiagnosticsSnapshot.AdapterRow, String> col = new TableColumn<>(heading);
        col.setCellValueFactory(data -> new SimpleStringProperty(value.apply(data.getValue())));
        return col;
    }

    private GridPane grid() {
        GridPane grid = new GridPane();
        grid.setHgap(24);
        grid.setVgap(6);
        return grid;
    }

    private void addRow(GridPane grid, int row, String key, String value) {
        Label keyLabel = new Label(key);
        keyLabel.setStyle("-fx-text-fill: #666;");
        keyLabel.setMinWidth(160);
        Label valueLabel = new Label(value);
        valueLabel.setStyle("-fx-font-family: 'Consolas', monospace;");
        grid.add(keyLabel, 0, row);
        grid.add(valueLabel, 1, row);
    }

    private void addCheck(GridPane grid, int row, String key, DiagnosticsSnapshot.CheckResult result) {
        Label keyLabel = new Label(key);
        keyLabel.setStyle("-fx-text-fill: #666;");
        keyLabel.setMinWidth(160);
        Label valueLabel = new Label((result.ok() ? "PASS  " : "FAIL  ") + result.detail());
        valueLabel.setStyle("-fx-font-family: 'Consolas', monospace; -fx-text-fill: "
                + (result.ok() ? "#1a7f37" : "#c22") + ";");
        grid.add(keyLabel, 0, row);
        grid.add(valueLabel, 1, row);
    }

    /** Runs the app's own graceful-shutdown hook, then exits the JVM. Never called implicitly. */
    public static void quit() {
        ClaudevShell instance = activeInstance;
        if (instance != null) {
            if (instance.trayIcon != null) {
                SystemTray.getSystemTray().remove(instance.trayIcon);
            }
            if (instance.workspacesPane != null) {
                instance.workspacesPane.shutdown();
            }
            if (instance.connectionsPane != null) {
                instance.connectionsPane.shutdown();
            }
        }
        onQuitRequested.run();
        Platform.exit();
    }
}
