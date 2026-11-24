package io.github.stevdrey.realtimevoicelab.ui;

import io.github.stevdrey.realtimevoicelab.realtime.RealtimeSession;
import io.github.stevdrey.realtimevoicelab.realtime.RealtimeTransport;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.css.PseudoClass;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

public final class WorkspaceController {
    private static final PseudoClass CONNECTED = PseudoClass.getPseudoClass("connected");
    private static final PseudoClass ERROR = PseudoClass.getPseudoClass("session-error");
    private static final PseudoClass DISCONNECT = PseudoClass.getPseudoClass("disconnect");

    private final RealtimeSession session;
    private final Tooltip connectTooltip = new Tooltip();
    private boolean disposed;

    @FXML
    private ComboBox<String> modelSelector;

    @FXML
    private Region statusDot;

    @FXML
    private Label sessionStatus;

    @FXML
    private ProgressIndicator sessionProgress;

    @FXML
    private StackPane settingsControl;

    @FXML
    private Button settingsButton;

    @FXML
    private StackPane micControl;

    @FXML
    private Button micButton;

    @FXML
    private Button connectButton;

    WorkspaceController(RealtimeTransport transport) {
        session = new RealtimeSession(transport, this::scheduleRender);
    }

    @FXML
    private void initialize() {
        modelSelector.getItems().setAll(RealtimeSession.DEFAULT_MODEL);
        modelSelector.setValue(RealtimeSession.DEFAULT_MODEL);
        sessionProgress.managedProperty().bind(sessionProgress.visibleProperty());
        modelSelector.accessibleTextProperty().bind(Bindings.createStringBinding(
                () -> "Model: " + modelSelector.getValue(), modelSelector.valueProperty()));

        installUnavailableTooltip(settingsControl, settingsButton);
        installUnavailableTooltip(micControl, micButton);
        connectButton.setTooltip(connectTooltip);
        connectButton.setOnAction(event -> {
            switch (session.status().state()) {
                case DISCONNECTED, ERROR -> session.connect(modelSelector.getValue());
                case CONNECTING, READY -> session.disconnect();
                case CLOSING -> { }
            }
            renderSession();
        });
        renderSession();
    }

    void dispose() {
        disposed = true;
        session.close();
    }

    private void scheduleRender() {
        Platform.runLater(() -> {
            if (!disposed) {
                renderSession();
            }
        });
    }

    private void renderSession() {
        var status = session.status();
        var state = status.state();
        sessionStatus.setText(switch (state) {
            case DISCONNECTED -> "Disconnected";
            case CONNECTING -> "Connecting…";
            case READY -> "Connected";
            case CLOSING -> "Disconnecting…";
            case ERROR -> failureText(status.failure());
        });
        statusDot.pseudoClassStateChanged(CONNECTED, state == RealtimeSession.State.READY);
        statusDot.pseudoClassStateChanged(ERROR, state == RealtimeSession.State.ERROR);
        sessionProgress.setVisible(state == RealtimeSession.State.CONNECTING
                || state == RealtimeSession.State.CLOSING);
        var action = switch (state) {
            case DISCONNECTED, ERROR -> "Connect";
            case CONNECTING -> "Cancel connection";
            case READY -> "Disconnect";
            case CLOSING -> "Disconnecting…";
        };
        connectButton.setAccessibleText(action);
        connectTooltip.setText(action);
        connectButton.setDisable(state == RealtimeSession.State.CLOSING);
        connectButton.pseudoClassStateChanged(DISCONNECT, state == RealtimeSession.State.READY);
    }

    private String failureText(RealtimeSession.Failure failure) {
        return switch (failure) {
            case MISSING_CREDENTIAL -> "OPENAI_API_KEY is not configured";
            case AUTHENTICATION -> "Authentication rejected";
            case CONNECTION -> "Connection failed";
            case TIMEOUT -> "Connection timed out";
            case PROTOCOL -> "Invalid Realtime event";
            case PROVIDER -> "Realtime session failed";
            case REMOTE_CLOSE -> "Server closed the session";
        };
    }

    private void installUnavailableTooltip(Node target, Button button) {
        // Disabled buttons do not receive hover events, so the tooltip goes on the button's hoverable wrapper.
        Tooltip.install(target, new Tooltip(button.getAccessibleText() + " (unavailable)"));
    }
}
