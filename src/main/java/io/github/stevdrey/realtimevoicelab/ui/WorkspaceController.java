package io.github.stevdrey.realtimevoicelab.ui;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

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
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

public final class WorkspaceController {
    private static final PseudoClass CONNECTED = PseudoClass.getPseudoClass("connected");
    private static final PseudoClass ERROR = PseudoClass.getPseudoClass("session-error");
    private static final PseudoClass DISCONNECT = PseudoClass.getPseudoClass("disconnect");

    private final RealtimeSession session;
    private final Tooltip connectTooltip = new Tooltip();
    private final AtomicBoolean renderPending = new AtomicBoolean();
    private RealtimeSession.State renderedState;
    private List<RealtimeSession.TurnView> renderedTurns = List.of();
    private boolean canSendText;
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

    @FXML
    private ScrollPane conversation;

    @FXML
    private VBox turnRows;

    @FXML
    private Label conversationEmpty;

    @FXML
    private TextField composer;

    @FXML
    private Button sendButton;

    @FXML
    private Button cancelButton;

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
            switch (renderedState) {
                case DISCONNECTED, ERROR -> session.connect(modelSelector.getValue());
                case CONNECTING, READY -> session.disconnect();
                case CLOSING -> { }
            }
            renderSession();
        });
        composer.setTextFormatter(new TextFormatter<>(change -> {
            int overflow = change.getControlNewText().length() - RealtimeSession.MAX_INPUT_CHARS;
            if (overflow <= 0) {
                return change;
            }
            var text = change.getText();
            if (overflow > text.length()) {
                return null;
            }
            // Keep as much of an oversized paste as fits.
            var kept = text.substring(0, text.length() - overflow);
            change.setText(kept);
            int caret = change.getRangeStart() + kept.length();
            change.selectRange(caret, caret);
            return change;
        }));
        composer.textProperty().addListener((observable, previous, text) -> updateSendButton());
        composer.setOnAction(event -> sendText());
        sendButton.setOnAction(event -> sendText());
        cancelButton.setOnAction(event -> {
            session.cancelText();
            renderSession();
        });
        renderSession();
    }

    void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        session.close();
        if (composer != null) {
            composer.clear();
        }
        if (turnRows != null) {
            turnRows.getChildren().clear();
        }
        renderedTurns = List.of();
    }

    private void scheduleRender() {
        if (renderPending.compareAndSet(false, true)) {
            Platform.runLater(() -> {
                renderPending.set(false);
                if (!disposed) {
                    renderSession();
                }
            });
        }
    }

    private void renderSession() {
        var workspace = session.workspace();
        var status = workspace.status();
        var state = status.state();
        renderedState = state;
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
        composer.setDisable(state != RealtimeSession.State.READY);
        canSendText = workspace.canSend();
        updateSendButton();
        cancelButton.setDisable(!workspace.canCancel());
        renderConversation(workspace.turns());
    }

    private void sendText() {
        if (session.sendText(composer.getText())) {
            composer.clear();
        }
        renderSession();
    }

    private void updateSendButton() {
        sendButton.setDisable(!canSendText || composer.getText().isBlank());
    }

    private void renderConversation(List<RealtimeSession.TurnView> turns) {
        conversationEmpty.setText(renderedState == RealtimeSession.State.READY
                ? "Send a message to start the conversation." : "Connect to start a conversation.");
        conversationEmpty.setVisible(turns.isEmpty());
        if (turns.equals(renderedTurns)) {
            return;
        }

        var rows = turnRows.getChildren();
        double hiddenHeight = turnRows.getHeight() - conversation.getViewportBounds().getHeight();
        boolean follow = hiddenHeight * (1 - conversation.getVvalue()) <= 36;
        rows.removeIf(row -> turns.isEmpty() || ((TurnRow) row).number < turns.getFirst().number());
        for (int index = 0; index < turns.size(); index++) {
            var turn = turns.get(index);
            if (index == rows.size()) {
                rows.add(new TurnRow(turn));
            }
            ((TurnRow) rows.get(index)).update(turn);
        }
        renderedTurns = turns;
        if (follow) {
            conversation.layout();
            conversation.setVvalue(conversation.getVmax());
        }
    }

    private static final class TurnRow extends VBox {
        private final long number;
        private final Label reply = new Label();
        private final Label status = new Label();

        TurnRow(RealtimeSession.TurnView turn) {
            number = turn.number();
            getStyleClass().add("conversation-turn");
            reply.getStyleClass().add("assistant-text");
            reply.setWrapText(true);
            status.getStyleClass().add("caption");
            status.setWrapText(true);
            reply.managedProperty().bind(reply.visibleProperty());
            status.managedProperty().bind(status.visibleProperty());
            var input = new Label(turn.input());
            input.setWrapText(true);
            var user = new Label("You");
            var assistant = new Label("Assistant");
            user.getStyleClass().add("caption");
            assistant.getStyleClass().add("caption");
            getChildren().addAll(user, input, assistant, reply, status);
        }

        void update(RealtimeSession.TurnView turn) {
            reply.setText(turn.output());
            reply.setVisible(!turn.output().isEmpty());
            var message = switch (turn.state()) {
                case WAITING -> "Waiting for response…";
                case RESPONDING -> "Responding…";
                case CANCELLING -> "Cancelling…";
                case COMPLETED -> turn.output().isEmpty() ? "No text returned" : "";
                case CANCELLED -> "Response cancelled";
                case FAILED -> "Text request failed";
                case INCOMPLETE -> "Response incomplete";
                case INTERRUPTED -> "Response interrupted";
                case CANCEL_REJECTED -> "Cancellation failed. Try again or disconnect.";
            };
            status.setText(message);
            status.setVisible(!message.isEmpty());
        }
    }

    private String failureText(RealtimeSession.Failure failure) {
        return switch (failure) {
            case MISSING_CREDENTIAL -> "OPENAI_API_KEY is not configured";
            case AUTHENTICATION -> "Authentication rejected";
            case CONNECTION -> "Connection failed";
            case TIMEOUT -> "Connection timed out";
            case RESPONSE_TIMEOUT -> "Response could not be identified. Reconnect to try again.";
            case PROTOCOL -> "Invalid Realtime event";
            case PROVIDER -> "Realtime session failed";
            case REMOTE_CLOSE -> "Server closed the session";
            case SESSION_LIMIT -> "Session time limit reached";
            case AUDIO_LIMIT -> "Audio turn limit reached";
            case OUTPUT_LIMIT -> "Response output limit reached";
            case TOOL_LIMIT -> "Session tool-call limit reached";
        };
    }

    private void installUnavailableTooltip(Node target, Button button) {
        // Disabled buttons do not receive hover events, so the tooltip goes on the button's hoverable wrapper.
        Tooltip.install(target, new Tooltip(button.getAccessibleText() + " (unavailable)"));
    }
}
