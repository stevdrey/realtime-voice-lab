package io.github.stevdrey.realtimevoicelab.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;

final class LabWorkspace extends VBox {
    private static final String WAVE_ICON = "M1 12h3l3-7 4 14 4-16 4 9h4";
    private static final String SETTINGS_ICON = "M10 2h4l.7 3 2 .9 2.7-1.5 2 3.5-2.2 2.1v3l2.2 2.1-2 3.5"
            + "-2.7-1.5-2 .9-.7 3h-4l-.7-3-2-.9-2.7 1.5-2-3.5L4.8 13v-3L2.6 7.9l2-3.5 2.7 1.5 2-.9z"
            + " M15 11.5a3 3 0 1 1-6 0 3 3 0 1 1 6 0z";
    private static final String MIC_ICON = "M12 2a4 4 0 0 0-4 4v6a4 4 0 0 0 8 0V6a4 4 0 0 0-4-4z"
            + " M5 11v1a7 7 0 0 0 14 0v-1 M12 19v4 M8 23h8";
    private static final String POWER_ICON = "M12 2v10 M6.4 5.4a8 8 0 1 0 11.2 0";

    LabWorkspace() {
        getStyleClass().add("app-shell");
        var workspace = new VBox(sessionToolbar(), mainContent(), idleWaveform(), audioControls(), metrics());
        workspace.getStyleClass().add("workspace");
        VBox.setVgrow(workspace, Priority.ALWAYS);
        getChildren().addAll(header(), workspace);
    }

    private HBox header() {
        var header = new HBox(10, icon(WAVE_ICON), label("Realtime Voice Lab", "app-title"), spacer(),
                label("JavaFX Desktop", "caption"));
        header.getStyleClass().add("app-header");
        return header;
    }

    private HBox sessionToolbar() {
        var statusDot = new Region();
        statusDot.getStyleClass().add("status-dot");
        var status = label("Disconnected", "session-status");
        status.setId("session-status");

        var model = new ComboBox<String>();
        model.setId("model-selector");
        model.getItems().add("gpt-realtime-2.1-mini");
        model.getSelectionModel().selectFirst();
        model.setAccessibleText("Default model: gpt-realtime-2.1-mini");
        model.setDisable(true);

        var toolbar = new HBox(8, statusDot, status, spacer(), model);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        return toolbar;
    }

    private SplitPane mainContent() {
        var emptyConversation = new StackPane(label("Connect to start a conversation.", "empty-message"));
        var conversation = new ScrollPane(emptyConversation);
        conversation.setId("conversation-content");
        conversation.setAccessibleText("Conversation");
        conversation.setFocusTraversable(true);
        conversation.setFitToWidth(true);
        conversation.setFitToHeight(true);
        conversation.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        var events = new ListView<String>();
        events.setId("event-list");
        events.setAccessibleText("Event Inspector");
        events.setPlaceholder(label("No events yet.", "empty-message"));

        var panels = new SplitPane(panel("CONVERSATION", conversation), panel("EVENT INSPECTOR", events));
        panels.setId("main-panels");
        panels.setDividerPositions(0.5);
        VBox.setVgrow(panels, Priority.ALWAYS);
        return panels;
    }

    private VBox panel(String title, Region content) {
        var heading = label(title, "panel-title");
        heading.setLabelFor(content);
        var panel = new VBox(8, heading, content);
        panel.getStyleClass().add("lab-panel");
        panel.setMinWidth(260);
        VBox.setVgrow(content, Priority.ALWAYS);
        return panel;
    }

    private StackPane idleWaveform() {
        // A single static shape draws the flat baseline without sampling audio or scheduling updates.
        var bars = new StringBuilder();
        for (int index = 0; index < 72; index++) {
            bars.append("M").append(index * 10).append(" 2a2 2 0 1 0 4 0 2 2 0 1 0-4 0z ");
        }
        var shape = new SVGPath();
        shape.setContent(bars.toString());
        var baseline = new Region();
        baseline.setShape(shape);
        baseline.getStyleClass().add("waveform-baseline");

        var waveform = new StackPane(baseline);
        waveform.getStyleClass().add("waveform");
        waveform.setAccessibleText("Idle waveform");
        return waveform;
    }

    private VBox audioControls() {
        var controls = new HBox(16,
                unavailableButton("settings-button", "Settings", SETTINGS_ICON, "round-control"),
                unavailableButton("mic-button", "Microphone", MIC_ICON, "mic-button"),
                unavailableButton("connect-button", "Connect", POWER_ICON, "round-control"));
        controls.setAlignment(Pos.CENTER);

        var section = new VBox(6, controls, label("Session controls unavailable", "caption"));
        section.setAlignment(Pos.CENTER);
        return section;
    }

    private Button unavailableButton(String id, String name, String glyph, String styleClass) {
        var button = new Button();
        button.setId(id);
        button.getStyleClass().add(styleClass);
        button.setGraphic(icon(glyph));
        button.setAccessibleText(name);
        button.setTooltip(new Tooltip(name + " (unavailable)"));
        button.setDisable(true);
        return button;
    }

    private HBox metrics() {
        var metrics = new HBox(16, metric("Latency"), metric("Session"), metric("Est. cost"));
        metrics.setId("metrics");
        metrics.getStyleClass().add("metrics");
        return metrics;
    }

    private VBox metric(String name) {
        var value = label("—", "metric-value");
        value.setAccessibleText(name + " unavailable");
        var column = new VBox(4, label(name, "caption"), value);
        column.setPrefWidth(0);
        HBox.setHgrow(column, Priority.ALWAYS);
        return column;
    }

    private Label label(String text, String styleClass) {
        var label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }

    private Region spacer() {
        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    private SVGPath icon(String content) {
        var icon = new SVGPath();
        icon.setContent(content);
        icon.getStyleClass().add("icon");
        return icon;
    }
}
