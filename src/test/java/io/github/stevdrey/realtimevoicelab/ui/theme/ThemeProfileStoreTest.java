package io.github.stevdrey.realtimevoicelab.ui.theme;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;

import io.github.stevdrey.realtimevoicelab.ui.theme.ThemeProfileStore.InvalidProfileException;
import io.github.stevdrey.realtimevoicelab.ui.theme.ThemeTokens.ColorRole;
import io.github.stevdrey.realtimevoicelab.ui.theme.ThemeTokens.Density;
import io.github.stevdrey.realtimevoicelab.ui.theme.ThemeTokens.FontFamily;
import io.github.stevdrey.realtimevoicelab.ui.theme.ThemeTokens.Radii;
import io.github.stevdrey.realtimevoicelab.ui.theme.ThemeTokens.Rgb;
import io.github.stevdrey.realtimevoicelab.ui.theme.ThemeTokens.Spacing;
import io.github.stevdrey.realtimevoicelab.ui.theme.ThemeTokens.Typography;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThemeProfileStoreTest {
    @TempDir Path directory;

    @Test
    void roundTripsNamedProfilesAcrossStoreInstancesAndReplacesOnlyTheNamedProfile() throws Exception {
        var profiles = directory.resolve("themes");
        var store = new ThemeProfileStore(profiles);
        var defaults = ThemeTokens.defaults();
        var colors = new EnumMap<>(defaults.colors());
        colors.put(ColorRole.FOCUS, new Rgb(0xfff4d5));
        var alternate = new ThemeTokens(colors, new Typography(FontFamily.SANS_SERIF, 16, 14, 22, 20, 18),
                new Spacing(5, 9, 13, 18), new Radii(4, 8, 10), new Density(44, 64));

        assertEquals(List.of(), store.names());
        assertFalse(Files.exists(profiles));
        store.save("Default copy", defaults);
        store.save("  Workshop_2 ", alternate);

        var restored = new ThemeProfileStore(profiles);
        assertEquals(List.of("default copy", "workshop_2"), restored.names());
        assertEquals(alternate, restored.load("WORKSHOP_2"));
        assertEquals(defaults, restored.load("Default copy"));
        assertThrows(InvalidProfileException.class, () -> restored.save("Workshop_2", null));
        assertEquals(alternate, restored.load("workshop_2"));

        store.save("Workshop_2", defaults);
        assertEquals(defaults, restored.load("workshop_2"));
        assertEquals(defaults, restored.load("default copy"));
        try (var files = Files.list(profiles)) {
            assertEquals(2, files.count());
        }
    }

    @Test
    void rejectsMalformedOversizedAndSemanticallyInvalidProfiles() throws Exception {
        var store = new ThemeProfileStore(directory);
        store.save("fixture", ThemeTokens.defaults());
        var file = directory.resolve("profile-fixture.json");
        var valid = Files.readString(file);
        assertTrue(valid.contains("\"body\" : 14"));
        assertTrue(valid.contains("\"version\" : 1"));
        var invalidProfiles = List.of(
                "{",
                "null",
                valid + " {}",
                valid.replace("\"version\" : 1", "\"version\" : 2"),
                valid.replace("\"version\" : 1", "\"version\" : 1, \"version\" : 1"),
                valid.replace("\"body\" : 14", "\"body\" : \"14\""),
                valid.replace("\"body\" : 14", "\"body\" : 14.1"),
                valid.replace("\"body\" : 14", "\"body\" : 0"),
                valid.replace("\"body\" : 14", "\"unknown\" : 14"),
                valid + " ".repeat(8193));
        for (var malformed : invalidProfiles) {
            Files.writeString(file, malformed);
            assertThrows(InvalidProfileException.class, () -> store.load("fixture"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "../outside", "..\\outside", "nested/file", "/absolute", "a.css", "Kelvin"})
    void rejectsNamesOutsideTheLocalProfileNamespace(String name) {
        var store = new ThemeProfileStore(directory);
        assertThrows(IllegalArgumentException.class, () -> store.load(name));
        assertThrows(IllegalArgumentException.class, () -> store.save(name, ThemeTokens.defaults()));
    }
}
