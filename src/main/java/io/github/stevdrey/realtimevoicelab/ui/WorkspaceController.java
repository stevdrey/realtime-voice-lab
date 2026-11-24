package io.github.stevdrey.realtimevoicelab.ui;

import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.shape.SVGPath;

public final class WorkspaceController {
    @FXML
    private ComboBox<String> modelSelector;

    @FXML
    private SVGPath waveformShape;

    @FXML
    private void initialize() {
        modelSelector.getSelectionModel().selectFirst();
        modelSelector.setAccessibleText("Default model: " + modelSelector.getValue());

        // A single static shape draws the flat baseline without sampling audio or scheduling updates.
        var bars = new StringBuilder();
        for (int index = 0; index < 72; index++) {
            bars.append("M").append(index * 10).append(" 2a2 2 0 1 0 4 0 2 2 0 1 0-4 0z ");
        }
        waveformShape.setContent(bars.toString());
    }
}
