package io.github.stevdrey.realtimevoicelab.ui.theme;

import java.util.EnumMap;
import java.util.Map;

/** Immutable candidate theme. Resolve it with {@link ThemeValidator} before use. */
public record ThemeTokens(
        Map<ColorRole, Rgb> colors,
        Typography typography,
        Spacing spacing,
        Radii radii,
        Density density) {

    private static final ThemeTokens DEFAULT = new ThemeTokens(
            Map.ofEntries(
                    Map.entry(ColorRole.APP, new Rgb(0x11151e)),
                    Map.entry(ColorRole.WORKSPACE, new Rgb(0x151923)),
                    Map.entry(ColorRole.PANEL, new Rgb(0x1e2430)),
                    Map.entry(ColorRole.CONTROL, new Rgb(0x272d3a)),
                    Map.entry(ColorRole.DIVIDER, new Rgb(0x343b49)),
                    Map.entry(ColorRole.TEXT, new Rgb(0xf3f4f6)),
                    Map.entry(ColorRole.SECONDARY, new Rgb(0xd1d5db)),
                    Map.entry(ColorRole.MUTED, new Rgb(0x9ca3af)),
                    Map.entry(ColorRole.ACCENT, new Rgb(0x818cf8)),
                    Map.entry(ColorRole.FOCUS, new Rgb(0xdbe1ff)),
                    Map.entry(ColorRole.HEADING, new Rgb(0xa5b4fc)),
                    Map.entry(ColorRole.PRIMARY, new Rgb(0x6366f1)),
                    Map.entry(ColorRole.PRIMARY_HOVER, new Rgb(0x37399e)),
                    Map.entry(ColorRole.ON_PRIMARY, new Rgb(0xffffff)),
                    Map.entry(ColorRole.SUCCESS, new Rgb(0x4ade80)),
                    Map.entry(ColorRole.OUTGOING, new Rgb(0x60a5fa)),
                    Map.entry(ColorRole.DANGER, new Rgb(0xf87171)),
                    Map.entry(ColorRole.WARNING, new Rgb(0xfbbf24))),
            new Typography(FontFamily.SYSTEM, 14, 12, 20, 19, 16),
            new Spacing(4, 8, 12, 16),
            new Radii(6, 9, 12),
            new Density(40, 60));

    public ThemeTokens {
        // Null roles or values count as missing so ThemeValidator can report them and fall back.
        var present = new EnumMap<ColorRole, Rgb>(ColorRole.class);
        if (colors != null) {
            colors.forEach((role, color) -> {
                if (role != null && color != null) {
                    present.put(role, color);
                }
            });
        }
        colors = Map.copyOf(present);
    }

    public static ThemeTokens defaults() {
        return DEFAULT;
    }

    public enum ColorRole {
        APP, WORKSPACE, PANEL, CONTROL, DIVIDER, TEXT, SECONDARY, MUTED, ACCENT, FOCUS,
        HEADING, PRIMARY, PRIMARY_HOVER, ON_PRIMARY, SUCCESS, OUTGOING, DANGER, WARNING
    }

    /** An opaque sRGB color encoded as 0xRRGGBB. */
    public record Rgb(int value) {
        public Rgb {
            if (value < 0 || value > 0xffffff) {
                throw new IllegalArgumentException("Expected a 24-bit RGB color");
            }
        }
    }

    public enum FontFamily {
        SYSTEM, SANS_SERIF
    }

    public record Typography(FontFamily family, int body, int caption, int title, int metric, int narrowMetric) {}

    public record Spacing(int tight, int small, int medium, int large) {}

    public record Radii(int control, int panel, int workspace) {}

    /** Minimum visual target dimensions in logical pixels. */
    public record Density(int secondaryButton, int microphoneButton) {}
}
