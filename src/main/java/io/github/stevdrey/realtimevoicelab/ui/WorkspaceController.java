package io.github.stevdrey.realtimevoicelab.ui;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;
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
import io.github.stevdrey.realtimevoicelab.realtime.SessionSettings.ToolPreset;
import io.github.stevdrey.realtimevoicelab.realtime.ToyWorkspace;
import io.github.stevdrey.realtimevoicelab.ui.theme.ThemeProfileStore;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.value.ObservableBooleanValue;
import javafx.css.PseudoClass;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;
import javafx.util.StringConverter;

public final class WorkspaceController {
    private static final double METRIC_GAP = 16;
    private static final double COMPACT_METRIC_GAP = 8;
    private static final double MIN_EQUAL_METRIC_WIDTH = 100;
    private static final double NARROW_METRICS_WIDTH = 280;
    private static final double COMPACT_WORKSPACE_HEIGHT = 620;

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
    private static final PseudoClass NARROW = PseudoClass.getPseudoClass("narrow");
    private static final PseudoClass COMPACT_HEIGHT = PseudoClass.getPseudoClass("compact-height");
    private static final PseudoClass MIDNIGHT = PseudoClass.getPseudoClass("midnight");
    private static final PseudoClass BLUE = PseudoClass.getPseudoClass("blue");
    private static final PseudoClass GREEN = PseudoClass.getPseudoClass("green");
    private static final PseudoClass AMBER = PseudoClass.getPseudoClass("amber");

    private final RealtimeSession session;
    private final ThemeProfileStore themeProfiles;
    private final WorkspaceTheme theme;
    private final Tooltip connectTooltip = new Tooltip();
    private final Tooltip micTooltip = new Tooltip();
    private final Tooltip costTooltip = new Tooltip();
    private final Timeline elapsedTimer = new Timeline(new KeyFrame(Duration.seconds(1), event -> renderElapsed()));
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

    @FXML private StackPane sceneRoot;
    @FXML private Button themeButton;
    @FXML private ThemeProfilesController themeProfilesController;

    @FXML
    private ComboBox<Model> modelSelector;

    @FXML
    private Label sessionMode;

    @FXML
    private ComboBox<TranscriptionModel> transcriptionSelector;

    // Restored when the saved settings leave Restricted Interviewer.
    private TranscriptionModel transcriptionBeforeRestricted;

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

    @FXML private Label latencyValue;
    @FXML private Label elapsedValue;
    @FXML private VBox costMetric;
    @FXML private Label costValue;
    @FXML private Label costState;
    @FXML private Label transcriptionCost;

    @FXML private HBox metrics;
    @FXML private VBox latencyMetric;
    @FXML private VBox elapsedMetric;
    @FXML private Region toyPanel;
    @FXML private Region toySwatch;
    @FXML private Label toyColor;
    @FXML private Label toyTheme;
    @FXML private Label toyCounter;

    WorkspaceController(RealtimeTransport transport, MicrophoneCapture microphone, SpeakerPlayback speaker,
                        ThemeProfileStore themeProfiles, WorkspaceTheme theme) {
        this.themeProfiles = themeProfiles;
        this.theme = theme;
        session = new RealtimeSession(transport, this::scheduleRender, microphone, speaker,
                this::scheduleInspectorRender, Platform::runLater);
    }

    @FXML
    private void initialize() {
        themeProfilesController.attach(themeButton, root, themeProfiles, theme);
        sceneRoot.heightProperty().addListener((observable, previous, height) ->
                root.pseudoClassStateChanged(COMPACT_HEIGHT, height.doubleValue() < COMPACT_WORKSPACE_HEIGHT));
        elapsedTimer.setCycleCount(Timeline.INDEFINITE);
        costState.managedProperty().bind(costState.visibleProperty());
        transcriptionCost.managedProperty().bind(transcriptionCost.visibleProperty());
        Tooltip.install(costMetric, costTooltip);
        var latencyHelp = "Time from sending a voice response request to receiving its first playable PCM chunk.";
        latencyValue.setTooltip(new Tooltip(latencyHelp));
        latencyValue.setAccessibleHelp(latencyHelp);
        modelSelector.getItems().setAll(Model.values());
        toyPanel.managedProperty().bind(toyPanel.visibleProperty());
        metrics.widthProperty().addListener((observable, previous, width) -> layoutMetrics());
        for (var column : List.of(latencyMetric, elapsedMetric, costMetric)) {
            for (var child : column.getChildren()) {
                if (child instanceof Label label) {
                    label.fontProperty().addListener((observable, previous, font) -> layoutMetrics());
                    label.textProperty().addListener((observable, previous, text) -> layoutMetrics());
                    label.managedProperty().addListener((observable, previous, managed) -> layoutMetrics());
                }
            }
        }
        sessionProgress.managedProperty().bind(sessionProgress.visibleProperty());
        modelSelector.accessibleTextProperty().bind(Bindings.createStringBinding(
                () -> "Model: " + modelSelector.getValue(), modelSelector.valueProperty()));
        modelSelector.setOnAction(event -> {
            var workspace = session.workspace();
            var saved = workspace.settings().values();
            var selected = modelSelector.getValue();
            if (selected != null && selected != saved.model()) {
                session.configure(saved.withModel(selected), workspace.status().state());
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
        composer.setTextFormatter(TextInputLimit.formatter(RealtimeSession.MAX_INPUT_CHARS));
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
                session.stopListening();
                mouseHeld = false;
            }
        });
    }

    private void installMicControls() {
        Tooltip.install(micControl, micTooltip);
        micButton.accessibleHelpProperty().bind(micTooltip.textProperty());
        micButton.setOnAction(event -> {
            if (session.workspace().settings().values().effectiveTurnDetection().automatic()) {
                toggleListening();
            }
        });
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
        // The modal and the workspace share sceneRoot. A release may land in either subtree.
        sceneRoot.addEventFilter(KeyEvent.KEY_RELEASED, event -> {
            var key = event.getCode();
            var scope = heldMicKeys.remove(key);
            if (scope != null && (scope == MicKeyScope.WORKSPACE || event.getTarget() == micButton)) {
                if (activeMicGesture == keyboardMicGesture(key)) {
                    finishMicGesture(true);
                }
                event.consume();
            }
        });
        sceneRoot.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            // Consume repeats across controls while the window retains focus.
            if (heldMicKeys.get(event.getCode()) == MicKeyScope.WORKSPACE) {
                event.consume();
            }
        });
        root.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                if (activeMicGesture != null) {
                    finishMicGesture(false);
                    event.consume();
                } else if (session.stopListening()) {
                    event.consume();
                }
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
        if (session.workspace().settings().values().effectiveTurnDetection().automatic()) {
            toggleListening();
            return;
        } else if (session.startAudio()) {
            activeMicGesture = gesture;
        }
        renderSession();
    }

    private void toggleListening() {
        if (!session.stopListening()) {
            session.startAudio();
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
        elapsedTimer.stop();
        if (settingsDialog != null) {
            settingsDialog.close();
        }
        if (themeProfilesController != null) {
            themeProfilesController.dispose();
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
        boolean restricted = settings.values().restrictedInterviewer();
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
        composer.setPromptText(restricted ? "start, answer: …, or clarify: …" : "Type a message…");
        canSendText = workspace.canSend();
        updateSendButton();
        cancelButton.setDisable(!workspace.canCancel());
        modelSelector.setValue(settings.values().model());
        modelSelector.setDisable(!disconnected);
        sessionMode.setText(restricted ? SessionSettings.Preset.RESTRICTED_INTERVIEWER.toString() : mode.toString());
        if (restricted && transcriptionSelector.getValue() != TranscriptionModel.OFF) {
            transcriptionBeforeRestricted = transcriptionSelector.getValue();
            transcriptionSelector.setValue(TranscriptionModel.OFF);
        } else if (!restricted && transcriptionBeforeRestricted != null) {
            transcriptionSelector.setValue(transcriptionBeforeRestricted);
            transcriptionBeforeRestricted = null;
        }
        transcriptionSelector.setDisable(!disconnected || restricted);
        settingsButton.setDisable(!settings.canEdit());
        settingsTooltip.setText(settings.canEdit() ? "Settings" : "Settings (unavailable while busy)");
        if (settingsDialog != null && state != settingsDialogState) {
            // The dialog reflects the state it opened in, so a session change closes it.
            settingsDialog.close();
        }
        boolean vad = settings.values().effectiveTurnDetection().automatic();
        renderAudio(workspace.microphone(), workspace.speaker(), mode, vad, settings.applying());
        renderConversation(workspace.turns(), mode, vad, restricted);
        renderMetrics(workspace);
        toyPanel.setVisible(settings.values().toolPreset() == ToolPreset.TOY_WORKSPACE);
        if (toyPanel.isVisible()) {
            renderToy(workspace.toy());
        }
    }

    private void renderMetrics(RealtimeSession.Workspace workspace) {
        var state = workspace.status().state();
        if (state == RealtimeSession.State.CONNECTING || state == RealtimeSession.State.READY) {
            elapsedTimer.play();
        } else {
            elapsedTimer.stop();
        }
        renderElapsed();
        var latency = workspace.audioLatency();
        var latencyText = latency == null ? null : latency.toMillis() + " ms";
        setMetric(latencyValue, "Latency", latencyText);
        var latencyHelp = latencyValue.getAccessibleHelp();
        latencyValue.getTooltip().setText(latencyText == null ? latencyHelp : latencyText + "\n" + latencyHelp);
        renderCost(workspace.usage());
    }

    private void layoutMetrics() {
        metrics.pseudoClassStateChanged(NARROW, metrics.getWidth() < NARROW_METRICS_WIDTH);
        double latencyWidth = metricColumnWidth(latencyMetric);
        double elapsedWidth = metricColumnWidth(elapsedMetric);
        double costWidth = metricColumnWidth(costMetric);
        double widestColumn = Math.max(costWidth, Math.max(latencyWidth, elapsedWidth));
        double equalColumnsWidth = 3 * Math.max(MIN_EQUAL_METRIC_WIDTH, widestColumn) + 2 * METRIC_GAP;
        boolean compact = metrics.getWidth() < equalColumnsWidth;
        metrics.setSpacing(compact ? COMPACT_METRIC_GAP : METRIC_GAP);
        latencyMetric.setMinWidth(compact ? latencyWidth : Region.USE_COMPUTED_SIZE);
        elapsedMetric.setMinWidth(compact ? elapsedWidth : Region.USE_COMPUTED_SIZE);
        costMetric.setMinWidth(compact ? costWidth : Region.USE_COMPUTED_SIZE);
        // Reserve space for complete labels before the adjacent toy pane takes its share.
        metrics.setMinWidth(latencyWidth + elapsedWidth + costWidth + 2 * COMPACT_METRIC_GAP);
    }

    private double metricColumnWidth(VBox column) {
        return Math.ceil(column.getChildren().stream()
                .filter(Node::isManaged).mapToDouble(child -> child.prefWidth(-1)).max().orElse(0));
    }

    private void renderElapsed() {
        var elapsed = session.elapsedTime();
        var value = elapsed == null ? null
                : String.format(Locale.ROOT, "%02d:%02d", elapsed.toMinutes(), elapsed.toSecondsPart());
        setMetric(elapsedValue, "Session elapsed", value);
    }

    private void renderCost(RealtimeSession.UsageView usage) {
        var details = new StringJoiner("\n");
        var amount = usage == null ? null : usage.estimatedUsd();
        boolean lowerBound = amount != null && usage.missingUsage();
        var formattedAmount = amount == null ? null : formatUsd(amount, lowerBound);
        var displayAmount = lowerBound ? "≥ " + formattedAmount : formattedAmount;
        details.add(formattedAmount == null ? "Estimated Realtime response cost in USD."
                : "Estimated Realtime response cost " + (lowerBound ? "at least " : "") + formattedAmount + " USD.");
        details.add("Actual charges may differ.");
        setMetric(costValue, "Estimated Realtime response cost", displayAmount);
        String state = "";
        boolean transcriptionUsed = usage != null && usage.transcriptionUsed();
        if (usage != null) {
            details.add("Session model: " + usage.model().id() + ".");
            if (usage.pricingDate() != null) {
                details.add("Rates dated " + usage.pricingDate() + ".");
            } else {
                details.add("Pricing unavailable for this model.");
                state = "Pricing unavailable";
            }
            if (usage.missingUsage()) {
                details.add("Some response usage was missing, incomplete or unpriceable.");
                state = "Usage unavailable";
            }
            if (usage.pending()) {
                details.add("Responses in flight may add charges as they report usage.");
                if (state.isEmpty()) {
                    state = "Pending usage";
                }
            } else if (amount == null && state.isEmpty()) {
                details.add("Waiting for the first response to report usage.");
            }
            if (usage.transcriptionEnabled()) {
                details.add("Input transcription is billed separately and excluded from this estimate.");
            }
            if (amount != null || usage.reportedInputTokens() > 0 || usage.reportedOutputTokens() > 0) {
                details.add("Reported tokens: " + usage.reportedInputTokens() + " input ("
                        + usage.reportedCachedTokens() + " cached), " + usage.reportedOutputTokens() + " output.");
            }
            if (usage.reportedReasoningTokens() != null) {
                details.add("Reported reasoning tokens included in output: " + usage.reportedReasoningTokens() + ".");
            }
            for (var quota : usage.rateLimits()) {
                details.add(String.format(Locale.ROOT, "Reported %s quota: %d / %d remaining. Reset delay at update: %.1f s.",
                        quota.name(), quota.remaining(), quota.limit(), quota.resetSeconds()));
            }
        }
        costState.setText(state);
        costState.setVisible(!state.isEmpty());
        transcriptionCost.setVisible(transcriptionUsed);
        var explanation = details.toString();
        costTooltip.setText(explanation);
        costValue.setAccessibleHelp(explanation);
    }

    private String formatUsd(BigDecimal amount, boolean lowerBound) {
        var rounded = amount.setScale(6, lowerBound ? RoundingMode.DOWN : RoundingMode.HALF_UP);
        if (amount.signum() > 0 && rounded.signum() == 0) {
            return lowerBound ? "$" + amount.stripTrailingZeros().toPlainString() : "<$0.000001";
        }
        return rounded.signum() == 0 ? "$0.00" : "$" + rounded.toPlainString();
    }

    private void setMetric(Label label, String name, String value) {
        label.setText(value == null ? "—" : value);
        label.setAccessibleText(value == null ? name + " unavailable" : name + " " + value);
    }

    private void renderToy(ToyWorkspace toy) {
        toyPanel.pseudoClassStateChanged(MIDNIGHT, toy.theme() == ToyWorkspace.Theme.MIDNIGHT);
        toySwatch.pseudoClassStateChanged(BLUE, toy.color() == ToyWorkspace.Color.BLUE);
        toySwatch.pseudoClassStateChanged(GREEN, toy.color() == ToyWorkspace.Color.GREEN);
        toySwatch.pseudoClassStateChanged(AMBER, toy.color() == ToyWorkspace.Color.AMBER);
        toyColor.setText("Color: " + toy.color().id());
        toyTheme.setText("Theme: " + toy.theme().id());
        toyCounter.setText("Counter: " + toy.counter());
    }

    private void renderAudio(RealtimeSession.MicrophoneView microphone, RealtimeSession.SpeakerView speaker,
                             Mode mode, boolean vad, boolean applyingSettings) {
        var state = microphone.state();
        var speakerState = speaker.state();
        boolean listening = vad && (state == RealtimeSession.MicState.OPENING
                || state == RealtimeSession.MicState.RECORDING);
        boolean cleaningSpeaker = speakerState == SpeakerState.CLEANING_UP || speakerState == SpeakerState.CLEANUP_TIMEOUT;
        var audioStatus = switch (speakerState) {
            case IDLE -> microphone.canStart() ? (vad ? "Start listening" : "Hold to talk") : "";
            case OPENING -> "Opening speaker…";
            case SPEAKING -> "Speaking…";
            case CLEANING_UP -> "Closing speaker…";
            case CLEANUP_TIMEOUT -> "Audio unavailable. Waiting for the speaker to close.";
            case UNAVAILABLE -> "Speaker unavailable. Check your output device.";
        };
        // Keep keyboard focus during speaker cleanup. The session prevents capture until the speaker is released.
        micButton.setDisable(renderedState != RealtimeSession.State.READY || mode == Mode.TEXT || applyingSettings
                || state == RealtimeSession.MicState.UNCONFIRMED);
        micButton.pseudoClassStateChanged(WAITING_FOR_SPEAKER, cleaningSpeaker && !vad);
        micButton.setAccessibleText(vad ? (listening ? "Stop listening" : "Start listening") : "Microphone");
        micTooltip.setText(mode == Mode.TEXT ? "Microphone unavailable in Text mode"
                : state == RealtimeSession.MicState.UNCONFIRMED ? "PCM microphone input was not confirmed"
                : vad && renderedState == RealtimeSession.State.READY
                ? listening ? "Click the microphone or press Esc to stop listening."
                            : "Click the microphone to listen for up to 30 seconds."
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
            case UNCONFIRMED -> "PCM microphone input was not confirmed";
            case OPENING -> "Opening microphone…";
            case RECORDING -> vad && playing ? "Listening · " + audioStatus
                    : String.format(Locale.ROOT, "%s · %.1f s", vad ? "Listening" : "Recording", microphone.seconds());
            case LIMIT -> vad ? "Listening limit reached." : "Recording limit reached. Release to send.";
            case STOPPING -> "Stopping microphone…";
            case SENDING -> "Sending audio…";
            case TOO_SHORT -> "Recording too short. Hold to talk.";
            case UNSUPPORTED -> "Microphone requires mono PCM16 at 24 kHz";
            case UNAVAILABLE -> "Microphone unavailable. Check your input device.";
            case BACKLOG -> "Audio upload fell behind. Listening stopped.";
        });
        themeProfilesController.renderRecording(recording, micStatus.getText());
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

    private void renderConversation(List<RealtimeSession.TurnView> turns, Mode mode, boolean vad, boolean restricted) {
        conversationEmpty.setText(renderedState == RealtimeSession.State.READY
                ? restricted ? "Type start to begin the interview."
                    : mode == Mode.TEXT ? "Send a message."
                        : vad ? "Send a message or start listening." : "Send a message or hold the microphone to talk."
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
        private final Label assistant = new Label();
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
            assistant.setText(turn.state() == RealtimeSession.TurnState.BLOCKED ? "Local redirect" : "Assistant");
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
                case BLOCKED -> "";
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
            case RESTRICTED_TOOL_CALL -> "Restricted Interviewer blocked a tool call. Reconnect to continue.";
            case SPEND_LIMIT -> "Estimated spend threshold reached";
            case SPEND_ESTIMATE_UNAVAILABLE -> "Estimated spend unavailable. Session stopped.";
            case SPEND_CONFIGURATION -> "Check the estimated spend limit and model pricing configuration.";
            case TRANSCRIPTION_CONFLICT -> "VAD needs Push to talk with gpt-realtime-whisper. Change turn detection or transcription.";
        };
    }

}
