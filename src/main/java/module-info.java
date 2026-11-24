module io.github.stevdrey.realtimevoicelab {
    requires javafx.controls;
    requires javafx.fxml;
    requires java.net.http;
    requires java.desktop;
    requires com.fasterxml.jackson.core;
    requires com.fasterxml.jackson.databind;

    exports io.github.stevdrey.realtimevoicelab.ui to javafx.graphics;
    opens io.github.stevdrey.realtimevoicelab.ui to javafx.fxml;
}
