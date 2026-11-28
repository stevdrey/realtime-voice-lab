package io.github.stevdrey.realtimevoicelab.ui.theme;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Locale;
import java.util.regex.Pattern;

import io.github.stevdrey.realtimevoicelab.ui.theme.ThemeTokens.ColorRole;
import io.github.stevdrey.realtimevoicelab.ui.theme.ThemeTokens.Density;
import io.github.stevdrey.realtimevoicelab.ui.theme.ThemeTokens.FontFamily;
import io.github.stevdrey.realtimevoicelab.ui.theme.ThemeTokens.Radii;
import io.github.stevdrey.realtimevoicelab.ui.theme.ThemeTokens.Rgb;
import io.github.stevdrey.realtimevoicelab.ui.theme.ThemeTokens.Spacing;
import io.github.stevdrey.realtimevoicelab.ui.theme.ThemeTokens.Typography;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThemeValidatorTest {
    private final ThemeTokens defaults = ThemeTokens.defaults();

    @Test
    void acceptsDefaultsAndKeepsTheirPaletteInSyncWithThePackagedStylesheet() throws IOException {
        var resolved = ThemeValidator.resolve(defaults);
        assertSame(defaults, resolved.theme());
        assertFalse(resolved.usesFallback());

        try (var stylesheet = getClass().getResourceAsStream("/styles.css")) {
            var css = new String(stylesheet.readAllBytes(), StandardCharsets.UTF_8);
            var declarations = Pattern.compile("(?m)^\\s*-rv-([a-z-]+): #([0-9a-f]{6});$").matcher(css);
            var cssColors = new EnumMap<ColorRole, Rgb>(ColorRole.class);
            while (declarations.find()) {
                var role = ColorRole.valueOf(declarations.group(1).replace('-', '_').toUpperCase(Locale.ROOT));
                assertNull(cssColors.put(role, new Rgb(Integer.parseInt(declarations.group(2), 16))),
                        "Duplicate CSS color " + role);
            }
            assertEquals(defaults.colors(), cssColors);
        }
    }

    @Test
    void acceptsAndSnapshotsAValidNonDefaultTheme() {
        var colors = new EnumMap<>(defaults.colors());
        var alternateFocus = new Rgb(0xfff4d5);
        colors.put(ColorRole.FOCUS, alternateFocus);
        var candidate = new ThemeTokens(colors,
                new Typography(FontFamily.SANS_SERIF, 16, 14, 22, 20, 18),
                new Spacing(5, 9, 13, 18), new Radii(4, 8, 10), new Density(44, 64));
        colors.clear();

        assertEquals(alternateFocus, candidate.colors().get(ColorRole.FOCUS));
        assertThrows(UnsupportedOperationException.class, () -> candidate.colors().clear());
        var resolved = ThemeValidator.resolve(candidate);
        assertSame(candidate, resolved.theme());
        assertTrue(resolved.violations().isEmpty());
    }

    @Test
    void rejectsOutOfRangeAndInconsistentMetricsWithOneFallback() {
        var candidate = new ThemeTokens(defaults.colors(),
                new Typography(null, 13, 16, 25, 17, 19),
                new Spacing(1, 8, 15, 21), new Radii(-1, 17, 12), new Density(39, 69));
        var resolved = ThemeValidator.resolve(candidate);

        assertSame(defaults, resolved.theme());
        assertTrue(resolved.usesFallback());
        assertEquals(12, resolved.violations().size());
        assertTrue(resolved.violations().stream().anyMatch(message -> message.startsWith("density.microphoneButton")));
        assertTrue(resolved.violations().stream().anyMatch(message -> message.startsWith("Typography narrow metric")));
        assertThrows(UnsupportedOperationException.class, () -> resolved.violations().clear());
    }

    @Test
    void rejectsUnreadableTextAndControlStatesWithoutPartiallyApplyingTheTheme() {
        var colors = new EnumMap<>(defaults.colors());
        colors.put(ColorRole.TEXT, colors.get(ColorRole.PANEL));
        colors.put(ColorRole.FOCUS, colors.get(ColorRole.PRIMARY));
        colors.put(ColorRole.ON_PRIMARY, colors.get(ColorRole.PRIMARY_HOVER));
        var candidate = new ThemeTokens(colors, defaults.typography(), defaults.spacing(), defaults.radii(),
                new Density(44, 64));
        var resolved = ThemeValidator.resolve(candidate);

        assertSame(defaults, resolved.theme());
        assertTrue(resolved.violations().contains("TEXT / PANEL contrast must be at least 4.5:1"));
        assertTrue(resolved.violations().contains("FOCUS / PRIMARY contrast must be at least 3.0:1"));
        assertTrue(resolved.violations().contains("ON_PRIMARY / PRIMARY_HOVER contrast must be at least 3.0:1"));
    }

    @Test
    void fallsBackForAbsentOrIncompleteCandidates() {
        var missingRole = new EnumMap<>(defaults.colors());
        missingRole.remove(ColorRole.ACCENT);
        var incomplete = new ThemeTokens(missingRole, null, null, null, null);

        var absent = ThemeValidator.resolve(null);
        assertSame(defaults, absent.theme());
        assertTrue(absent.usesFallback());

        var partial = ThemeValidator.resolve(incomplete);
        assertSame(defaults, partial.theme());
        assertTrue(partial.usesFallback());
        assertTrue(partial.violations().contains("Missing color ACCENT"));
        assertEquals(5, partial.violations().size());
    }

    @Test
    void rejectsColorsOutsideOpaqueRgbAndComputesReferenceContrastRatios() {
        var black = new Rgb(0x000000);
        var white = new Rgb(0xffffff);
        assertThrows(IllegalArgumentException.class, () -> new Rgb(-1));
        assertThrows(IllegalArgumentException.class, () -> new Rgb(0x1000000));
        assertEquals(1, ThemeValidator.contrastRatio(black, black), 1e-9);
        assertEquals(21, ThemeValidator.contrastRatio(white, black), 1e-9);
        assertEquals(5.252, ThemeValidator.contrastRatio(new Rgb(0xff0000), black), 1e-9);
        assertTrue(ThemeValidator.contrastRatio(new Rgb(0x777777), white) < 4.5);
        assertTrue(ThemeValidator.contrastRatio(new Rgb(0x767676), white) >= 4.5);
    }
}
