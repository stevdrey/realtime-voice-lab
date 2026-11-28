package io.github.stevdrey.realtimevoicelab.ui.theme;

import java.util.EnumMap;

import io.github.stevdrey.realtimevoicelab.ui.theme.ThemeTokens.ColorRole;
import io.github.stevdrey.realtimevoicelab.ui.theme.ThemeTokens.Rgb;

/** The committed appearance, one temporary preview and one reversible change. */
public record WorkspaceAppearance(ThemeTokens committed, BuiltIn preview, ThemeTokens previous) {
    public static final WorkspaceAppearance DEFAULT = new WorkspaceAppearance(ThemeTokens.defaults(), null, null);

    public ThemeTokens displayed() {
        return preview == null ? committed : preview.tokens();
    }

    public String committedId() {
        for (var builtIn : BuiltIn.values()) {
            if (builtIn.tokens().equals(committed)) {
                return builtIn.id();
            }
        }
        return "custom";
    }

    public WorkspaceAppearance preview(BuiltIn candidate) {
        return new WorkspaceAppearance(committed, candidate, previous);
    }

    public WorkspaceAppearance discardPreview() {
        return preview == null ? this : new WorkspaceAppearance(committed, null, previous);
    }

    public WorkspaceAppearance apply(BuiltIn candidate) {
        if (committed.equals(candidate.tokens())) {
            return discardPreview();
        }
        return new WorkspaceAppearance(candidate.tokens(), null, committed);
    }

    public WorkspaceAppearance undo() {
        if (preview != null) {
            return discardPreview();
        }
        return previous == null ? this : new WorkspaceAppearance(previous, null, null);
    }

    public enum BuiltIn {
        DEFAULT("default", "Default", ThemeTokens.defaults()),
        OCEAN("ocean", "Ocean", ocean());

        private final String id;
        private final String label;
        private final ThemeTokens tokens;

        BuiltIn(String id, String label, ThemeTokens tokens) {
            this.id = id;
            this.label = label;
            this.tokens = tokens;
        }

        public String id() {
            return id;
        }

        public String label() {
            return label;
        }

        public ThemeTokens tokens() {
            return tokens;
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
}
