package io.github.stevdrey.realtimevoicelab.ui;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.EnumMap;
import java.util.Locale;

import io.github.stevdrey.realtimevoicelab.ui.theme.ThemeTokens;
import io.github.stevdrey.realtimevoicelab.ui.theme.ThemeTokens.ColorRole;
import io.github.stevdrey.realtimevoicelab.ui.theme.ThemeTokens.Rgb;
import io.github.stevdrey.realtimevoicelab.ui.theme.ThemeValidator;
import javafx.application.Platform;
import javafx.scene.Scene;

final class WorkspaceTheme {
    static final ThemeTokens OCEAN = ocean();

    private ThemeTokens current = ThemeTokens.defaults();
    private String overlay;

    ThemeTokens current() {
        return current;
    }

    boolean apply(Scene scene, ThemeTokens candidate) {
        if (!Platform.isFxApplicationThread()) {
            throw new IllegalStateException("Apply workspace themes on the FX thread");
        }
        var resolved = ThemeValidator.resolve(candidate);
        var next = resolved.theme();
        if (overlay != null && current.equals(next)) {
            return !resolved.usesFallback();
        }

        var stylesheets = scene.getStylesheets();
        var nextOverlay = stylesheet(next);
        if (overlay == null) {
            stylesheets.add(nextOverlay);
        } else {
            int index = stylesheets.indexOf(overlay);
            if (index < 0) {
                stylesheets.add(nextOverlay);
            } else {
                stylesheets.set(index, nextOverlay);
            }
        }
        overlay = nextOverlay;
        current = next;
        return !resolved.usesFallback();
    }

    private String stylesheet(ThemeTokens theme) {
        var colors = new StringBuilder();
        for (var role : ColorRole.values()) {
            var property = role.name().toLowerCase(Locale.ROOT).replace('_', '-');
            colors.append(String.format(Locale.ROOT, "    -rv-%s: #%06x;%n", property, theme.colors().get(role).value()));
        }
        var typography = theme.typography();
        var spacing = theme.spacing();
        var radii = theme.radii();
        var density = theme.density();
        var font = switch (typography.family()) {
            case SYSTEM -> "System";
            case SANS_SERIF -> "SansSerif";
        };
        // JavaFX needs literal sizes with ASCII digits, regardless of the JVM's format locale.
        var css = String.format(Locale.ROOT, """
                .root {
                %s    -fx-font-family: "%s";
                    -fx-font-size: %dpx;
                }
                .app-title, .dialog-title { -fx-font-size: %dpx; }
                .caption, .session-status, .combo-box, .panel-title, .event-inspector .list-cell,
                .event-inspector .titled-pane { -fx-font-size: %dpx; }
                .metric-value { -fx-font-size: %dpx; }
                .metrics:narrow .metric-value { -fx-font-size: %dpx; }
                """, colors, font, typography.body(), typography.title(),
                typography.caption(), typography.metric(), typography.narrowMetric())
                + String.format(Locale.ROOT, """
                .app-shell, .workspace { -fx-padding: %4$dpx; -fx-spacing: %3$dpx; }
                .app-shell:compact-height { -fx-padding: %2$dpx %4$dpx; -fx-spacing: %1$dpx; }
                .app-shell:compact-height .workspace { -fx-padding: %1$dpx %4$dpx; -fx-spacing: %1$dpx; }
                .app-header { -fx-padding: 0 %1$dpx; }
                .lab-panel { -fx-padding: %3$dpx; }
                .theme-dialog { -fx-padding: %4$dpx; -fx-spacing: %3$dpx; }
                .lab-panel, .session-toolbar, .composer-actions, .theme-fields, .theme-actions {
                    -fx-spacing: %2$dpx;
                }
                .app-shell:compact-height .lab-panel { -fx-padding: %1$dpx %3$dpx; -fx-spacing: %1$dpx; }
                .toy-workspace { -fx-padding: %2$dpx %3$dpx; -fx-hgap: %3$dpx; -fx-vgap: %1$dpx; }
                .conversation-turn { -fx-spacing: %1$dpx; -fx-padding: %2$dpx; }
                .audio-controls, .workspace-footer { -fx-spacing: %4$dpx; }
                .workspace-footer { -fx-padding: %2$dpx 0 0 0; }
                .metrics { -fx-spacing: %4$dpx; }
                .metrics:compact-metrics { -fx-spacing: %2$dpx; }
                """, spacing.tight(), spacing.small(), spacing.medium(), spacing.large())
                + String.format(Locale.ROOT, """
                .combo-box, .text-field, .text-action, .settings-dialog .text-area,
                .settings-dialog .text-area .content, .event-inspector .titled-pane > .title {
                    -fx-background-radius: %1$dpx;
                    -fx-border-radius: %1$dpx;
                }
                .theme-dialog .combo-box > .arrow-button { -fx-background-radius: 0 %1$dpx %1$dpx 0; }
                .lab-panel, .theme-dialog, .toy-workspace {
                    -fx-background-radius: %2$dpx; -fx-border-radius: %2$dpx;
                }
                .workspace { -fx-background-radius: %3$dpx; }
                """, radii.control(), radii.panel(), radii.workspace())
                + String.format(Locale.ROOT, """
                .round-control {
                    -fx-min-width: %1$dpx; -fx-min-height: %1$dpx;
                    -fx-pref-width: %1$dpx; -fx-pref-height: %1$dpx;
                }
                .mic-button {
                    -fx-min-width: %2$dpx; -fx-min-height: %2$dpx;
                    -fx-pref-width: %2$dpx; -fx-pref-height: %2$dpx;
                }
                """, density.secondaryButton(), density.microphoneButton());
        return "data:text/css;base64," + Base64.getEncoder().encodeToString(css.getBytes(StandardCharsets.UTF_8));
    }

    private static ThemeTokens ocean() {
        var defaults = ThemeTokens.defaults();
        var colors = new EnumMap<>(defaults.colors());
        colors.put(ColorRole.ACCENT, new Rgb(0x5eead4));
        colors.put(ColorRole.FOCUS, new Rgb(0xccfbf1));
        colors.put(ColorRole.HEADING, new Rgb(0x99f6e4));
        colors.put(ColorRole.PRIMARY, new Rgb(0x0f766e));
        colors.put(ColorRole.PRIMARY_HOVER, new Rgb(0x0d514b));
        return new ThemeTokens(colors, defaults.typography(), defaults.spacing(), defaults.radii(), defaults.density());
    }
}
