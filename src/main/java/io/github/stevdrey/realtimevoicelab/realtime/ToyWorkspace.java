package io.github.stevdrey.realtimevoicelab.realtime;

import java.util.Locale;

public record ToyWorkspace(Color color, Theme theme, int counter) {
    static final ToyWorkspace DEFAULT = new ToyWorkspace(Color.INDIGO, Theme.SLATE, 0);
    static final int MAX_COUNTER = 100;
    static final int MAX_DELTA = 10;

    public enum Color {
        INDIGO, BLUE, GREEN, AMBER;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum Theme {
        SLATE, MIDNIGHT;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
