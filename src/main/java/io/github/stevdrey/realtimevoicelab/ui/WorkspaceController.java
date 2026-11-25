package io.github.stevdrey.realtimevoicelab.ui;

import java.io.IOException;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.stevdrey.realtimevoicelab.audio.MicrophoneCapture;
import io.github.stevdrey.realtimevoicelab.audio.SpeakerPlayback;
import io.github.stevdrey.realtimevoicelab.realtime.RealtimeSession;
import io.github.stevdrey.realtimevoicelab.realtime.RealtimeSession.SpeakerState;
import io.github.stevdrey.realtimevoicelab.realtime.RealtimeSession.TranscriptionModel;
import io.github.stevdrey.realtimevoicelab.realtime.RealtimeTransport;
import io.github.stevdrey.realtimevoicelab.realtime.SessionSettings;
import io.github.stevdrey.realtimevoicelab.realtime.SessionSettings.Mode;
import io.github.stevdrey.realtimevoicelab.realtime.SessionSettings.Model;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.value.ObservableBooleanValue;
import javafx.css.PseudoClass;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.StringConverter;

public final class WorkspaceController {
    private enum MicGesture {
        MOUSE, SPACE, ENTER
    }

    private enum MicKeyScope {
        WORKSPACE, MICROPHONE_ONLY
    }

    private static final PseudoClass CONNECTED = PseudoClass.getPseudoClass("connected");
    private static final PseudoClass ERROR = PseudoClass.getPseudoClass("session-error");
    private static final PseudoClass DISCONNECT = PseudoClass.getPseudoClass("disconnect");
    private static final PseudoClass RECORDING = PseudoClass.getPseudoClass("recording");
    private static final PseudoClass WAITING_FOR_SPEAKER = PseudoClass.getPseudoClass("waiting-for-speaker");

    private final RealtimeSession session;
    private final Tooltip connectTooltip = new Tooltip();
    private final Tooltip micTooltip = new Tooltip();
    private final Tooltip settingsTooltip = new Tooltip("Settings");
    private final AtomicBoolean renderPending = new AtomicBoolean();
    private final AtomicBoolean inspectorRenderPending = new AtomicBoolean();
    private final EnumMap<KeyCode, MicKeyScope> heldMicKeys = new EnumMap<>(KeyCode.class);
    private RealtimeSession.State renderedState;
    private List<RealtimeSession.TurnView> renderedTurns = List.of();
    private boolean canSendText;
    private boolean mouseHeld;
    private MicGesture activeMicGesture;
    private boolean disposed;
    private Stage settingsDialog;
    private RealtimeSession.State settingsDialogState;

    @FXML
    private VBox root;

    @FXML
    private ComboBox<Model> modelSelector;

    @FXML
    private Label sessionMode;

    @FXML
    private ComboBox<TranscriptionModel> transcriptionSelector;

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
    private Label micStatus;

    @FXML
    private ProgressBar micLevel;

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

    @FXML
    private EventInspectorController eventInspectorController;

    WorkspaceController(RealtimeTransport transport, MicrophoneCapture microphone, SpeakerPlayback speaker) {
        session = new RealtimeSession(transport, this::scheduleRender, microphone, speaker,
                this::scheduleInspectorRender);
    }

    @FXML
    private void initialize() {
        modelSelector.getItems().setAll(Model.values());
        sessionProgress.managedProperty().bind(sessionProgress.visibleProperty());
        modelSelector.accessibleTextProperty().bind(Bindings.createStringBinding(
                () -> "Model: " + modelSelector.getValue(), modelSelector.valueProperty()));
        modelSelector.setOnAction(event -> {
            var workspace = session.workspace();
            var saved = workspace.settings().values();
            var selected = modelSelector.getValue();
            if (selected != null && selected != saved.model()) {
                session.configure(new SessionSettings(selected, saved.voice(), saved.mode(),
                        saved.instructions()), workspace.status().state());
                renderSession();
            }
        });
        transcriptionSelector.getItems().setAll(TranscriptionModel.values());
        transcriptionSelector.setConverter(new StringConverter<>() {
            @Override
            public String toString(TranscriptionModel model) {
                return model == null || model == TranscriptionModel.OFF ? "Off" : model.modelId();
            }

            @Override
            public TranscriptionModel fromString(String value) {
                // The selector is read-only and never parses typed text.
                return null;
            }
        });
        transcriptionSelector.setValue(TranscriptionModel.OFF);
        var transcriptionHelp = "Optional. Select a model before connecting to display approximate microphone transcripts.";
        transcriptionSelector.setTooltip(new Tooltip(transcriptionHelp));
        transcriptionSelector.setAccessibleHelp(transcriptionHelp);

        // Disabled buttons do not receive hover events, so the wrapper shows the same tooltip while disabled.
        settingsButton.setTooltip(settingsTooltip);
        Tooltip.install(settingsControl, settingsTooltip);
        settingsButton.setOnAction(event -> showSettings());
        installMicControls();
        connectButton.setTooltip(connectTooltip);
        connectButton.setOnAction(event -> {
            switch (renderedState) {
                case DISCONNECTED, ERROR -> session.connectSelected(transcriptionSelector.getValue());
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
            // Keep as much of an oversized paste as fits without splitting a supplementary character.
            int end = text.length() - overflow;
            if (end > 0 && Character.isSurrogatePair(text.charAt(end - 1), text.charAt(end))) {
                end--;
            }
            var kept = text.substring(0, end);
            change.setText(kept);
            int caret = change.getRangeStart() + kept.length();
            change.selectRange(caret, caret);
            return change;
        }));
        composer.textProperty().addListener((observable, previous, text) -> updateSendButton());
        composer.setOnAction(event -> sendText());
        sendButton.setOnAction(event -> sendText());
        cancelButton.setOnAction(event -> {
            activeMicGesture = null;
            session.cancelTurn();
            renderSession();
        });
        renderSession();
    }

    private void showSettings() {
        var workspace = session.workspace();
        if (!workspace.settings().canEdit() || settingsDialog != null) {
            return;
        }
        var state = workspace.status().state();
        var dialog = new Stage();
        dialog.initOwner(root.getScene().getWindow());
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.setTitle("Session Settings");
        var loader = new FXMLLoader(getClass().getResource("session-settings.fxml"));
        loader.setController(new SessionSettingsController(dialog, workspace.settings(),
                state == RealtimeSession.State.READY, requested -> {
                    boolean applied = session.configure(requested, state);
                    renderSession();
                    return applied;
                }));
        try {
            var scene = new Scene(loader.load());
            scene.getStylesheets().setAll(root.getScene().getStylesheets());
            dialog.setScene(scene);
        } catch (IOException error) {
            throw new IllegalStateException("Session settings could not be loaded", error);
        }
        dialog.setResizable(false);
        dialog.setOnHidden(event -> settingsDialog = null);
        settingsDialog = dialog;
        settingsDialogState = state;
        dialog.show();
    }

    void watchFocus(ObservableBooleanValue windowFocus) {
        windowFocus.addListener((observable, previous, focused) -> {
            if (!focused) {
                // A release can occur outside this window. Keep only the microphone's repeat guard.
                heldMicKeys.replaceAll((key, scope) -> MicKeyScope.MICROPHONE_ONLY);
                finishMicGesture(false);
                mouseHeld = false;
            }
        });
    }

    private void installMicControls() {
        Tooltip.install(micControl, micTooltip);
        micButton.accessibleHelpProperty().bind(micTooltip.textProperty());
        micLevel.managedProperty().bind(micLevel.visibleProperty());
        micButton.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                if (!mouseHeld && activeMicGesture == null) {
                    micButton.requestFocus();
                    mouseHeld = true;
                    startMicGesture(MicGesture.MOUSE);
                }
                event.consume();
            }
        });
        root.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> {
            if (mouseHeld && event.getButton() == MouseButton.PRIMARY) {
                mouseHeld = false;
                if (activeMicGesture == MicGesture.MOUSE) {
                    finishMicGesture(true);
                }
                event.consume();
            }
        });
        micButton.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            var key = event.getCode();
            var gesture = keyboardMicGesture(key);
            if (gesture != null) {
                // A repeat on the microphone after refocus restores its protection across controls.
                var previousScope = heldMicKeys.put(key, MicKeyScope.WORKSPACE);
                if (previousScope == null && !mouseHeld && activeMicGesture == null) {
                    startMicGesture(gesture);
                }
                event.consume();
            }
        });
        root.addEventFilter(KeyEvent.KEY_RELEASED, event -> {
            var key = event.getCode();
            var scope = heldMicKeys.remove(key);
            if (scope != null && (scope == MicKeyScope.WORKSPACE || event.getTarget() == micButton)) {
                if (activeMicGesture == keyboardMicGesture(key)) {
                    finishMicGesture(true);
                }
                event.consume();
            }
        });
        root.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            var key = event.getCode();
            // Consume repeats across controls while the window retains focus.
            if (heldMicKeys.get(key) == MicKeyScope.WORKSPACE) {
                event.consume();
            } else if (key == KeyCode.ESCAPE && activeMicGesture != null) {
                finishMicGesture(false);
                event.consume();
            }
        });
        micButton.focusedProperty().addListener((observable, previous, focused) -> {
            if (!focused) {
                finishMicGesture(false);
            }
        });
    }

    private MicGesture keyboardMicGesture(KeyCode key) {
        return switch (key) {
            case SPACE -> MicGesture.SPACE;
            case ENTER -> MicGesture.ENTER;
            default -> null;
        };
    }

    private void startMicGesture(MicGesture gesture) {
        if (session.startAudio()) {
            activeMicGesture = gesture;
        }
        renderSession();
    }

    private void finishMicGesture(boolean submit) {
        if (activeMicGesture == null || disposed) {
            return;
        }
        activeMicGesture = null;
        if (submit) {
            session.releaseAudio();
        } else {
            session.cancelAudio();
        }
        renderSession();
    }

    void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        if (settingsDialog != null) {
            settingsDialog.close();
        }
        session.close();
        if (composer != null) {
            composer.clear();
        }
        if (turnRows != null) {
            turnRows.getChildren().clear();
        }
        if (eventInspectorController != null) {
            eventInspectorController.render(List.of());
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

    // Event bursts refresh only the inspector, not the whole workspace.
    private void scheduleInspectorRender() {
        if (inspectorRenderPending.compareAndSet(false, true)) {
            Platform.runLater(() -> {
                inspectorRenderPending.set(false);
                if (!disposed) {
                    eventInspectorController.render(session.inspectedEvents());
                }
            });
        }
    }

    private void renderSession() {
        var workspace = session.workspace();
        var status = workspace.status();
        var state = status.state();
        var settings = workspace.settings();
        var mode = settings.values().mode();
        boolean disconnected = state == RealtimeSession.State.DISCONNECTED || state == RealtimeSession.State.ERROR;
        renderedState = state;
        sessionStatus.setText(switch (state) {
            case DISCONNECTED -> "Disconnected";
            case CONNECTING -> "Connecting…";
            case READY -> settings.applying() ? "Applying settings…" : "Connected";
            case CLOSING -> "Disconnecting…";
            case ERROR -> failureText(status.failure());
        });
        statusDot.pseudoClassStateChanged(CONNECTED, state == RealtimeSession.State.READY);
        statusDot.pseudoClassStateChanged(ERROR, state == RealtimeSession.State.ERROR);
        sessionProgress.setVisible(settings.applying() || state == RealtimeSession.State.CONNECTING
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
        modelSelector.setValue(settings.values().model());
        modelSelector.setDisable(!disconnected);
        sessionMode.setText(mode.toString());
        transcriptionSelector.setDisable(!disconnected);
        settingsButton.setDisable(!settings.canEdit());
        settingsTooltip.setText(settings.canEdit() ? "Settings" : "Settings (unavailable while busy)");
        if (settingsDialog != null && state != settingsDialogState) {
            // The dialog reflects the state it opened in, so a session change closes it.
            settingsDialog.close();
        }
        renderAudio(workspace.microphone(), workspace.speaker(), mode, settings.applying());
        renderConversation(workspace.turns(), mode);
    }

    private void renderAudio(RealtimeSession.MicrophoneView microphone, RealtimeSession.SpeakerView speaker,
                             Mode mode, boolean applyingSettings) {
        var state = microphone.state();
        var speakerState = speaker.state();
        boolean cleaningSpeaker = speakerState == SpeakerState.CLEANING_UP || speakerState == SpeakerState.CLEANUP_TIMEOUT;
        var audioStatus = switch (speakerState) {
            case IDLE -> microphone.canStart() ? "Hold to talk" : "";
            case OPENING -> "Opening speaker…";
            case SPEAKING -> "Speaking…";
            case CLEANING_UP -> "Closing speaker…";
            case CLEANUP_TIMEOUT -> "Audio unavailable. Waiting for the speaker to close.";
            case UNAVAILABLE -> "Speaker unavailable. Check your output device.";
        };
        // Keep keyboard focus during speaker cleanup. The session prevents capture until the speaker is released.
        micButton.setDisable(renderedState != RealtimeSession.State.READY || mode == Mode.TEXT || applyingSettings
                || state == RealtimeSession.MicState.UNCONFIRMED);
        micButton.pseudoClassStateChanged(WAITING_FOR_SPEAKER, cleaningSpeaker);
        micTooltip.setText(mode == Mode.TEXT ? "Microphone unavailable in Text mode"
                : state == RealtimeSession.MicState.UNCONFIRMED ? "Manual PCM microphone input was not confirmed"
                : cleaningSpeaker ? audioStatus
                : renderedState == RealtimeSession.State.READY
                ? "Hold the microphone or Space to record. Release to send. Esc cancels."
                : "Microphone (connect to record)");
        boolean recording = state == RealtimeSession.MicState.RECORDING;
        boolean speaking = speakerState == SpeakerState.SPEAKING;
        boolean playing = speaking || speakerState == SpeakerState.OPENING;
        micButton.pseudoClassStateChanged(RECORDING, recording);
        micLevel.setVisible(recording || speaking);
        micLevel.setAccessibleText(speaking ? "Speaker level" : "Microphone level");
        micLevel.setProgress(speaking ? speaker.level() : microphone.level());
        cancelButton.setText(playing ? "Stop" : "Cancel");
        cancelButton.setAccessibleText(playing ? "Stop playback" : "Cancel turn");
        micStatus.setText(mode == Mode.TEXT ? audioStatus : switch (state) {
            case IDLE -> audioStatus;
            case UNCONFIRMED -> "Manual PCM microphone input was not confirmed";
            case OPENING -> "Opening microphone…";
            case RECORDING -> String.format(Locale.ROOT, "Recording · %.1f s", microphone.seconds());
            case LIMIT -> "Recording limit reached. Release to send.";
            case STOPPING -> "Stopping microphone…";
            case SENDING -> "Sending audio…";
            case TOO_SHORT -> "Recording too short. Hold to talk.";
            case UNSUPPORTED -> "Microphone requires mono PCM16 at 24 kHz";
            case UNAVAILABLE -> "Microphone unavailable. Check your input device.";
        });
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

    private void renderConversation(List<RealtimeSession.TurnView> turns, Mode mode) {
        conversationEmpty.setText(renderedState == RealtimeSession.State.READY
                ? mode == Mode.TEXT ? "Send a message." : "Send a message or hold the microphone to talk."
                : "Connect to start a conversation.");
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
        private final Label user = new Label("You");
        private final Label input = new Label();
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
            input.setWrapText(true);
            var assistant = new Label("Assistant");
            user.getStyleClass().add("caption");
            assistant.getStyleClass().add("caption");
            getChildren().addAll(user, input, assistant, reply, status);
        }

        void update(RealtimeSession.TurnView turn) {
            user.setText(turn.inputTranscript() == RealtimeSession.InputTranscriptState.COMPLETED
                    ? "You · Approximate transcript" : "You");
            input.setText(switch (turn.inputTranscript()) {
                case OFF, COMPLETED -> turn.input();
                case PENDING -> "Voice input · Transcribing…";
                case FAILED -> "Voice input · Transcription unavailable";
            });
            reply.setText(turn.output());
            reply.setVisible(!turn.output().isEmpty());
            var message = switch (turn.state()) {
                case WAITING -> "Waiting for response…";
                case RESPONDING -> "Responding…";
                case CANCELLING -> "Cancelling…";
                case COMPLETED -> turn.output().isEmpty()
                        ? (turn.voice() ? "No transcript returned" : "No text returned") : "";
                case CANCELLED -> "Response cancelled";
                case FAILED -> "Request failed";
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
            case ITEM_ACK_TIMEOUT -> "Message was not acknowledged. Reconnect to try again.";
            case RESPONSE_TIMEOUT -> "Response could not be identified. Reconnect to try again.";
            case CANCEL_TIMEOUT -> "Cancellation timed out. Reconnect to try again.";
            case PROTOCOL -> "Invalid Realtime event";
            case PROVIDER -> "Realtime session failed";
            case REMOTE_CLOSE -> "Server closed the session";
            case SETTINGS_REJECTED -> "Settings rejected. Review them and reconnect.";
            case SETTINGS_MISMATCH -> "Settings confirmation did not match. Reconnect to retry.";
            case SETTINGS_TIMEOUT -> "Settings were not acknowledged. Reconnect to retry.";
            case SESSION_LIMIT -> "Session time limit reached";
            case AUDIO_LIMIT -> "Audio turn limit reached";
            case OUTPUT_LIMIT -> "Response output limit reached";
            case TOOL_LIMIT -> "Session tool-call limit reached";
        };
    }

}
