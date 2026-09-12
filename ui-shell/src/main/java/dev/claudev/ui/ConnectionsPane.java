package dev.claudev.ui;

import dev.claudev.domain.Connection;
import dev.claudev.domain.ConnectionId;
import dev.claudev.domain.ConnectionKind;
import dev.claudev.provider.connection.ScanPage;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * WP10a: a manual Redis connection screen — {@code adapter-redis} already connects for real (WP7),
 * this exposes it. A connection is global (not workspace-scoped, per {@code Connection}'s domain
 * shape), so this is its own top-level tab rather than nested inside {@link WorkspacesPane}.
 */
public final class ConnectionsPane extends BorderPane {

    private final ConnectionControlPort controlPort;
    /** Two threads, not one — same reasoning as {@code WorkspacesPane}'s poller: a slow action (a real network connect/reconnect) should never queue behind an unrelated refresh, or vice versa. */
    private final ScheduledExecutorService worker = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "claudev-connections-worker");
        t.setDaemon(true);
        return t;
    });

    private final ObservableList<Connection> connectionRows = FXCollections.observableArrayList();
    private final TableView<Connection> connectionTable = new TableView<>(connectionRows);
    private final ListView<String> keyList = new ListView<>();
    private final TextArea valueArea = new TextArea();
    private String scanCursor = "";
    private boolean scanComplete = true;

    public ConnectionsPane(ConnectionControlPort controlPort) {
        this.controlPort = controlPort;
        setPadding(new Insets(16));
        setTop(new VBox(6, subtitle(), header()));
        setCenter(splitPane());
        refresh();
    }

    public void shutdown() {
        worker.shutdownNow();
    }

    private Label subtitle() {
        Label label = new Label(
                "Point this at a Redis you already have running (local, WSL2, Docker, or remote) — "
                        + "this app never installs or manages Redis itself. Redis-only for now.");
        label.setWrapText(true);
        label.setStyle("-fx-text-fill: #666; -fx-font-size: 11px;");
        return label;
    }

    private HBox header() {
        Button connect = new Button("Connect to Redis...");
        connect.setOnAction(e -> promptConnect());

        Button delete = new Button("Delete connection");
        delete.setOnAction(e -> deleteSelected());

        return new HBox(8, connect, delete);
    }

    private javafx.scene.control.SplitPane splitPane() {
        connectionTable.getColumns().add(column("Connection", this::labelFor));
        connectionTable.setPrefWidth(280);
        connectionTable.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            keyList.getItems().clear();
            valueArea.clear();
            scanCursor = "";
            scanComplete = selected == null;
        });

        Button loadKeys = new Button("Load more keys");
        loadKeys.setOnAction(e -> loadMoreKeys());

        keyList.getSelectionModel().selectedItemProperty().addListener((obs, old, key) -> {
            if (key != null) {
                loadValue(key);
            }
        });

        VBox keysPane = new VBox(8, loadKeys, new Label("Keys (SCAN — a live, possibly-incomplete view)"), keyList,
                new Label("Value"), valueArea);
        VBox.setVgrow(keyList, Priority.ALWAYS);
        valueArea.setEditable(false);
        valueArea.setPrefRowCount(6);

        javafx.scene.control.SplitPane split = new javafx.scene.control.SplitPane(connectionTable, keysPane);
        split.setOrientation(Orientation.HORIZONTAL);
        split.setDividerPositions(0.35);
        return split;
    }

    private String labelFor(Connection connection) {
        if (connection.kind() instanceof ConnectionKind.Remote remote) {
            return remote.host() + ":" + remote.port() + (remote.tls() ? " (tls)" : "");
        }
        return connection.id().value().toString();
    }

    private TableColumn<Connection, String> column(String heading, java.util.function.Function<Connection, String> value) {
        TableColumn<Connection, String> col = new TableColumn<>(heading);
        col.setCellValueFactory(data -> new SimpleStringProperty(value.apply(data.getValue())));
        return col;
    }

    private void promptConnect() {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Connect to Redis");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        TextField host = new TextField("127.0.0.1");
        TextField port = new TextField("6379");
        PasswordField password = new PasswordField();

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.setPadding(new Insets(12));
        grid.addRow(0, new Label("Host:"), host);
        grid.addRow(1, new Label("Port:"), port);
        grid.addRow(2, new Label("Password (optional):"), password);
        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(bt -> {
            if (bt == ButtonType.OK) {
                submitConnect(host.getText(), port.getText(), password.getText());
            }
            return null;
        });
        dialog.showAndWait();
    }

    private void submitConnect(String host, String portText, String password) {
        int port;
        try {
            port = Integer.parseInt(portText.trim());
        } catch (NumberFormatException e) {
            showError("Port must be a number: " + portText);
            return;
        }
        Optional<String> pw = (password == null || password.isBlank()) ? Optional.empty() : Optional.of(password);

        worker.submit(() -> {
            try {
                controlPort.connectToRedis(host.trim(), port, pw);
                Platform.runLater(this::refresh);
            } catch (RuntimeException e) {
                Platform.runLater(() -> showError("Could not connect: " + e.getMessage()));
            }
        });
    }

    private void deleteSelected() {
        Connection selected = connectionTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "Delete connection \"" + labelFor(selected) + "\"?", ButtonType.YES, ButtonType.CANCEL);
        confirm.setHeaderText(null);
        confirm.showAndWait().filter(bt -> bt == ButtonType.YES).ifPresent(bt -> worker.submit(() -> {
            controlPort.deleteConnection(selected.id());
            Platform.runLater(this::refresh);
        }));
    }

    private void loadMoreKeys() {
        Connection selected = connectionTable.getSelectionModel().getSelectedItem();
        if (selected == null || scanComplete) {
            return;
        }
        worker.submit(() -> {
            try {
                ScanPage page = controlPort.scanKeys(selected.id(), scanCursor, 50);
                Platform.runLater(() -> {
                    keyList.getItems().addAll(page.keys());
                    scanCursor = page.cursor();
                    scanComplete = page.complete();
                });
            } catch (RuntimeException e) {
                Platform.runLater(() -> showError("Scan failed: " + e.getMessage()));
            }
        });
    }

    private void loadValue(String key) {
        Connection selected = connectionTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        worker.submit(() -> {
            try {
                Optional<String> value = controlPort.getValue(selected.id(), key);
                Platform.runLater(() -> valueArea.setText(value.orElse("(not a string / no value)")));
            } catch (RuntimeException e) {
                Platform.runLater(() -> valueArea.setText("(error: " + e.getMessage() + ")"));
            }
        });
    }

    private void refresh() {
        worker.submit(() -> {
            var connections = controlPort.listConnections();
            Platform.runLater(() -> connectionRows.setAll(connections));
        });
    }

    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        alert.setHeaderText(null);
        alert.showAndWait();
    }
}
