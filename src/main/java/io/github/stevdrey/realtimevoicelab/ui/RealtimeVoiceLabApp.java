package io.github.stevdrey.realtimevoicelab.ui;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

public final class RealtimeVoiceLabApp extends Application {
    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        var scene = new Scene(new LabWorkspace(), 1100, 720);
        scene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());

        stage.setTitle("Realtime Voice Lab");
        stage.setMinWidth(760);
        stage.setMinHeight(520);
        stage.setScene(scene);
        stage.show();
    }
}
