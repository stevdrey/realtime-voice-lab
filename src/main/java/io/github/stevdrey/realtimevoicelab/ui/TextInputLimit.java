package io.github.stevdrey.realtimevoicelab.ui;

import javafx.scene.control.TextFormatter;

final class TextInputLimit {
    private TextInputLimit() {
    }

    static TextFormatter<String> formatter(int maxChars) {
        return new TextFormatter<>(change -> {
            int overflow = change.getControlNewText().length() - maxChars;
            if (overflow <= 0) {
                return change;
            }
            var text = change.getText();
            if (overflow > text.length()) {
                return null;
            }
            // Keep as much of an oversized paste as fits without splitting a supplementary character.
            int end = text.length() - overflow;
            if (end > 0 && Character.isSurrogatePair(text.charAt(end - 1), text.charAt(end))) {
                end--;
            }
            change.setText(text.substring(0, end));
            int caret = change.getRangeStart() + end;
            change.selectRange(caret, caret);
            return change;
        });
    }
}
