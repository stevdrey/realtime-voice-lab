package io.github.stevdrey.realtimevoicelab.ui;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.robot.Robot;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class RealtimeVoiceLabAppTest {
    @BeforeAll
    static void startToolkit() throws InterruptedException {
        var ready = new CountDownLatch(1);
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            ready.countDown();
        });
        assertTrue(ready.await(10, TimeUnit.SECONDS), "JavaFX toolkit did not start");
    }

    @AfterAll
    static void stopToolkit() {
        Platform.exit();
    }

    @Test
    void showsTooltipsWhenHoveringDisabledSessionButtons() throws Exception {
        var stage = onFxThread(Stage::new);
        try {
            onFxThread(() -> {
                new RealtimeVoiceLabApp().start(stage);
                return null;
            });

            assertHoveredTooltip(stage, "#settings-button", "Settings (unavailable)");
            assertHoveredTooltip(stage, "#mic-button", "Microphone (unavailable)");
            assertHoveredTooltip(stage, "#connect-button", "Connect (unavailable)");
        } finally {
            onFxThread(() -> {
                stage.close();
                return null;
            });
        }
    }

    @Test
    void opensDisconnectedWithAccessibleDisabledControlsAndUnknownMetrics() throws Exception {
        withWindow(stage -> {
            assertTrue(stage.isShowing());
            assertEquals("Realtime Voice Lab", stage.getTitle());

            var scene = stage.getScene();
            assertEquals("Disconnected", ((Label) scene.lookup("#session-status")).getText());
            var model = (ComboBox<?>) scene.lookup("#model-selector");
            assertEquals("gpt-realtime-2.1-mini", model.getValue());
            assertEquals("Default model: gpt-realtime-2.1-mini", model.getAccessibleText());
            assertTrue(model.isDisabled());
            assertDisabledButton(scene, "#settings-button", "Settings");
            assertDisabledButton(scene, "#mic-button", "Microphone");
            assertDisabledButton(scene, "#connect-button", "Connect");

            var metrics = scene.getRoot().lookupAll(".metric-value");
            assertEquals(3, metrics.size());
            for (var metric : metrics) {
                assertEquals("—", ((Label) metric).getText());
            }
            var events = (ListView<?>) scene.lookup("#event-list");
            assertTrue(events.getItems().isEmpty());
            var conversation = (ScrollPane) scene.lookup("#conversation-content");
            assertTrue(conversation.isFocusTraversable());

            var panels = (SplitPane) scene.lookup("#main-panels");
            var conversationHeading = (Label) panels.getItems().get(0).lookup(".panel-title");
            var inspectorHeading = (Label) panels.getItems().get(1).lookup(".panel-title");
            assertSame(conversation, conversationHeading.getLabelFor());
            assertSame(events, inspectorHeading.getLabelFor());
        });
    }

    @Test
    void keepsBothDarkPanelsAndControlsVisibleAtMinimumSize() throws Exception {
        withWindow(stage -> {
            assertEquals(760, stage.getMinWidth());
            assertEquals(520, stage.getMinHeight());

            var scene = stage.getScene();
            var root = scene.getRoot();
            assertEquals(1100, scene.getWidth());
            assertEquals(720, scene.getHeight());
            var panels = (SplitPane) scene.lookup("#main-panels");
            assertEquals(0.5, panels.getDividerPositions()[0], 0.01);

            root.resize(740, 480); // Allow for the window decorations at the minimum stage size.
            panels.setDividerPositions(0.42);
            root.layout();

            assertEquals(0.42, panels.getDividerPositions()[0], 0.01);
            var conversation = (Region) panels.getItems().get(0);
            var inspector = (Region) panels.getItems().get(1);
            assertTrue(conversation.getWidth() >= 260);
            assertTrue(inspector.getWidth() >= 260);
            assertTrue(conversation.getHeight() > 100);
            assertEquals(Color.web("#1e2430"), conversation.getBackground().getFills().getFirst().getFill());
            var baseline = (Region) scene.lookup(".waveform-baseline");
            assertTrue(baseline.getBoundsInParent().getWidth() > 0);
            assertTrue(baseline.getShape().getBoundsInLocal().getWidth() > 0);

            var metrics = scene.lookup("#metrics");
            var footerBounds = metrics.localToScene(metrics.getBoundsInLocal());
            assertTrue(footerBounds.getMaxY() <= root.getLayoutBounds().getHeight());
        });
    }

    private void assertDisabledButton(Scene scene, String selector, String name) {
        var button = (Button) scene.lookup(selector);
        assertTrue(button.isDisabled());
        assertEquals(name, button.getAccessibleText());
    }

    private void assertHoveredTooltip(Stage stage, String selector, String expectedText) throws Exception {
        onFxThread(() -> {
            var button = stage.getScene().lookup(selector);
            var bounds = button.localToScreen(button.getBoundsInLocal());
            new Robot().mouseMove(bounds.getCenterX(), bounds.getCenterY());
            return null;
        });

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            boolean showing = onFxThread(() -> Window.getWindows().stream().anyMatch(window ->
                    window instanceof Tooltip tooltip && expectedText.equals(tooltip.getText())));
            if (showing) {
                return;
            }
            Thread.sleep(25);
        }
        fail("Hovering over " + selector + " did not show: " + expectedText);
    }

    private void withWindow(Consumer<Stage> checks) throws Exception {
        onFxThread(() -> {
            var stage = new Stage();
            try {
                new RealtimeVoiceLabApp().start(stage);
                checks.accept(stage);
            } finally {
                stage.close();
            }
            return null;
        });
    }

    private <T> T onFxThread(Callable<T> action) throws Exception {
        var run = new FutureTask<>(action);
        Platform.runLater(run);
        return run.get(10, TimeUnit.SECONDS);
    }
}
