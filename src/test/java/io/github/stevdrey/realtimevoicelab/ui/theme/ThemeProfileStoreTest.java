package io.github.stevdrey.realtimevoicelab.ui.theme;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;

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
import static org.junit.jupiter.api.Assumptions.abort;

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

    @Test
    void capsNewProfilesAndListsTheFirstNamesInOrder() throws Exception {
        var store = new ThemeProfileStore(directory);
        fillProfileStore(store);
        var names = store.names();
        assertEquals(ThemeProfileStore.MAX_PROFILES, names.size());
        assertEquals("p001", names.getFirst());
        assertEquals("p100", names.getLast());
        assertThrows(InvalidProfileException.class, () -> store.save("p101", ThemeTokens.defaults()));
        store.save("P050", ThemeTokens.defaults());
        assertEquals(names, store.names());
    }

    @Test
    void rejectsASymbolicLinkAsAProfileDestinationAtTheCap() throws Exception {
        var store = new ThemeProfileStore(directory);
        var target = Path.of("profile-p001.json");
        var link = directory.resolve("profile-z-extra.json");
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            abort("This test filesystem cannot create symbolic links");
        }
        fillProfileStore(store);
        var originalTarget = Files.readString(directory.resolve(target));

        assertThrows(InvalidProfileException.class, () -> store.save("z-extra", ThemeTokens.defaults()));
        assertEquals(target, Files.readSymbolicLink(link));
        assertEquals(originalTarget, Files.readString(directory.resolve(target)));
        try (var paths = Files.list(directory)) {
            assertEquals(ThemeProfileStore.MAX_PROFILES,
                    paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).count());
        }
        store.save("p050", ThemeTokens.defaults());
        assertEquals(ThemeTokens.defaults(), store.load("p050"));
        assertEquals(ThemeProfileStore.MAX_PROFILES, store.names().size());
    }

    private void fillProfileStore(ThemeProfileStore store) throws IOException {
        for (int i = ThemeProfileStore.MAX_PROFILES; i > 0; i--) {
            store.save(String.format(Locale.ROOT, "p%03d", i), ThemeTokens.defaults());
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
