module io.github.stevdrey.realtimevoicelab {
    requires javafx.controls;
    requires javafx.fxml;

    exports io.github.stevdrey.realtimevoicelab.ui to javafx.graphics;
    opens io.github.stevdrey.realtimevoicelab.ui to javafx.fxml;
}
