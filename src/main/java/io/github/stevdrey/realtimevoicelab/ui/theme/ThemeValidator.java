package io.github.stevdrey.realtimevoicelab.ui.theme;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.stevdrey.realtimevoicelab.ui.theme.ThemeTokens.ColorRole;
import io.github.stevdrey.realtimevoicelab.ui.theme.ThemeTokens.Rgb;

import static io.github.stevdrey.realtimevoicelab.ui.theme.ThemeTokens.ColorRole.*;

public final class ThemeValidator {
    private static final double TEXT_CONTRAST = 4.5;
    private static final double CONTROL_CONTRAST = 3.0;

    private ThemeValidator() {}

    /** Resolve the entire theme, falling back atomically when any token is missing or invalid. */
    public static Resolution resolve(ThemeTokens candidate) {
        if (candidate == null) {
            return new Resolution(ThemeTokens.defaults(), List.of("Theme is missing"));
        }

        var violations = new ArrayList<String>();
        validateColors(candidate.colors(), violations);
        validateTypography(candidate.typography(), violations);
        validateSpacing(candidate.spacing(), violations);
        validateRadii(candidate.radii(), violations);
        validateDensity(candidate.density(), violations);
        return new Resolution(violations.isEmpty() ? candidate : ThemeTokens.defaults(), violations);
    }

    public record Resolution(ThemeTokens theme, List<String> violations) {
        public Resolution {
            violations = List.copyOf(violations);
        }

        public boolean usesFallback() {
            return !violations.isEmpty();
        }
    }

    private static void validateColors(Map<ColorRole, Rgb> colors, List<String> violations) {
        for (var role : ColorRole.values()) {
            if (!colors.containsKey(role)) {
                violations.add("Missing color " + role);
            }
        }

        requireContrast(colors, violations, TEXT_CONTRAST, TEXT, APP, WORKSPACE, PANEL, CONTROL, DIVIDER);
        requireContrast(colors, violations, TEXT_CONTRAST, SECONDARY, WORKSPACE, PANEL, CONTROL, DIVIDER);
        requireContrast(colors, violations, TEXT_CONTRAST, MUTED, APP, WORKSPACE, PANEL, CONTROL);
        // The toy workspace's Midnight state uses the APP background.
        requireContrast(colors, violations, TEXT_CONTRAST, HEADING, PANEL, APP);
        requireContrast(colors, violations, TEXT_CONTRAST, SUCCESS, WORKSPACE, PANEL, CONTROL);
        requireContrast(colors, violations, TEXT_CONTRAST, OUTGOING, PANEL, CONTROL);
        requireContrast(colors, violations, TEXT_CONTRAST, DANGER, WORKSPACE);

        // Text inputs use APP text on an ACCENT selection, and a visible selection against their surface.
        requireContrast(colors, violations, TEXT_CONTRAST, APP, ACCENT);
        requireContrast(colors, violations, CONTROL_CONTRAST, ACCENT, WORKSPACE, PANEL, CONTROL, DIVIDER);
        requireContrast(colors, violations, CONTROL_CONTRAST, FOCUS,
                APP, WORKSPACE, PANEL, CONTROL, DIVIDER, PRIMARY, PRIMARY_HOVER);
        requireContrast(colors, violations, CONTROL_CONTRAST, ON_PRIMARY, PRIMARY, PRIMARY_HOVER);
        requireContrast(colors, violations, CONTROL_CONTRAST, DANGER, CONTROL, DIVIDER, PRIMARY_HOVER);
        // Toy swatches sit on PANEL, or on APP in the Midnight state.
        requireContrast(colors, violations, CONTROL_CONTRAST, WARNING, APP, PANEL);
        requireContrast(colors, violations, CONTROL_CONTRAST, SUCCESS, APP);
        requireContrast(colors, violations, CONTROL_CONTRAST, OUTGOING, APP);
        requireContrast(colors, violations, CONTROL_CONTRAST, MUTED, DIVIDER);
    }

    private static void requireContrast(Map<ColorRole, Rgb> colors, List<String> violations, double minimum,
                                        ColorRole foreground, ColorRole... backgrounds) {
        var text = colors.get(foreground);
        for (var background : backgrounds) {
            var surface = colors.get(background);
            if (text != null && surface != null && contrastRatio(text, surface) < minimum) {
                violations.add(foreground + " / " + background + " contrast must be at least " + minimum + ":1");
            }
        }
    }

    static double contrastRatio(Rgb first, Rgb second) {
        double firstLuminance = luminance(first);
        double secondLuminance = luminance(second);
        return (Math.max(firstLuminance, secondLuminance) + 0.05)
                / (Math.min(firstLuminance, secondLuminance) + 0.05);
    }

    private static double luminance(Rgb color) {
        int rgb = color.value();
        return 0.2126 * linearChannel(rgb >> 16)
                + 0.7152 * linearChannel((rgb >> 8) & 0xff)
                + 0.0722 * linearChannel(rgb & 0xff);
    }

    private static double linearChannel(int channel) {
        double sRgb = channel / 255.0;
        return sRgb <= 0.04045 ? sRgb / 12.92 : Math.pow((sRgb + 0.055) / 1.055, 2.4);
    }

    private static void validateTypography(ThemeTokens.Typography typography, List<String> violations) {
        if (typography == null) {
            violations.add("Typography is missing");
            return;
        }
        if (typography.family() == null) {
            violations.add("Typography family is missing");
        }
        requireRange(violations, "typography.body", typography.body(), 14, 18);
        requireRange(violations, "typography.caption", typography.caption(), 12, 16);
        requireRange(violations, "typography.title", typography.title(), 18, 24);
        requireRange(violations, "typography.metric", typography.metric(), 16, 24);
        requireRange(violations, "typography.narrowMetric", typography.narrowMetric(), 16, 20);
        if (typography.caption() > typography.body()) {
            violations.add("Typography caption must not exceed the body size");
        }
        if (typography.narrowMetric() > typography.metric()) {
            violations.add("Typography narrow metric must not exceed the metric size");
        }
    }

    private static void validateSpacing(ThemeTokens.Spacing spacing, List<String> violations) {
        if (spacing == null) {
            violations.add("Spacing is missing");
            return;
        }
        requireRange(violations, "spacing.tight", spacing.tight(), 2, 6);
        requireRange(violations, "spacing.small", spacing.small(), 6, 10);
        requireRange(violations, "spacing.medium", spacing.medium(), 10, 14);
        requireRange(violations, "spacing.large", spacing.large(), 14, 20);
    }

    private static void validateRadii(ThemeTokens.Radii radii, List<String> violations) {
        if (radii == null) {
            violations.add("Radii are missing");
            return;
        }
        requireRange(violations, "radii.control", radii.control(), 0, 12);
        requireRange(violations, "radii.panel", radii.panel(), 0, 16);
        requireRange(violations, "radii.workspace", radii.workspace(), 0, 20);
    }

    private static void validateDensity(ThemeTokens.Density density, List<String> violations) {
        if (density == null) {
            violations.add("Density is missing");
            return;
        }
        requireRange(violations, "density.secondaryButton", density.secondaryButton(), 40, 48);
        requireRange(violations, "density.microphoneButton", density.microphoneButton(), 60, 68);
    }

    private static void requireRange(List<String> violations, String token, int value, int min, int max) {
        if (value < min || value > max) {
            violations.add(token + " must be between " + min + " and " + max + " px");
        }
    }
}
