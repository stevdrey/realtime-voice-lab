package io.github.stevdrey.realtimevoicelab.ui.theme;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

/** Local storage for appearance tokens. Call file operations off the FX thread. */
public final class ThemeProfileStore {
    public static final int MAX_NAME_LENGTH = 40;
    public static final int MAX_PROFILES = 100;
    private static final int VERSION = 1;
    private static final int MAX_PROFILE_BYTES = 8192;
    private static final String PREFIX = "profile-";
    private static final String SUFFIX = ".json";
    private static final Pattern NAME = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9 _-]{0," + (MAX_NAME_LENGTH - 1) + "}");
    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS,
                    DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                    DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .build();

    private final Path directory;

    public ThemeProfileStore(Path directory) {
        this.directory = directory;
    }

    public static String normalizeName(String name) {
        var stripped = name.strip();
        if (!NAME.matcher(stripped).matches()) {
            throw new IllegalArgumentException("Use 1–40 letters, digits, spaces, hyphens or underscores, starting with a letter or digit.");
        }
        return stripped.toLowerCase(Locale.ROOT);
    }

    public List<String> names() throws IOException {
        try (var paths = Files.list(directory)) {
            return paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .map(path -> path.getFileName().toString())
                    .filter(file -> file.startsWith(PREFIX) && file.endsWith(SUFFIX))
                    .map(file -> file.substring(PREFIX.length(), file.length() - SUFFIX.length()))
                    .filter(name -> NAME.matcher(name).matches() && name.equals(name.strip())
                            && name.equals(name.toLowerCase(Locale.ROOT)))
                    .sorted()
                    .limit(MAX_PROFILES)
                    .toList();
        } catch (NoSuchFileException missingDirectory) {
            return List.of();
        }
    }

    public ThemeTokens load(String name) throws IOException {
        var path = profilePath(name);
        if (!Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isRegularFile()) {
            throw new InvalidProfileException();
        }
        byte[] bytes;
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(MAX_PROFILE_BYTES + 1);
        }
        if (bytes.length > MAX_PROFILE_BYTES) {
            throw new InvalidProfileException();
        }

        StoredProfile profile;
        try {
            profile = JSON.readValue(bytes, StoredProfile.class);
        } catch (IOException malformedJson) {
            throw new InvalidProfileException();
        }
        if (profile == null || profile.version() != VERSION) {
            throw new InvalidProfileException();
        }
        return validTokens(profile.tokens());
    }

    public void save(String name, ThemeTokens tokens) throws IOException {
        var path = profilePath(name);
        var profile = new StoredProfile(VERSION, validTokens(tokens));
        Files.createDirectories(directory);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) && names().size() >= MAX_PROFILES) {
            throw new InvalidProfileException("Up to " + MAX_PROFILES
                    + " theme profiles can be saved. Use an existing name to replace one.");
        }
        var temp = Files.createTempFile(directory, ".profile-", ".tmp");
        try {
            var bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(profile);
            Files.write(temp, bytes);
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private Path profilePath(String name) {
        return directory.resolve(PREFIX + normalizeName(name) + SUFFIX);
    }

    private ThemeTokens validTokens(ThemeTokens tokens) throws InvalidProfileException {
        var resolved = ThemeValidator.resolve(tokens);
        if (resolved.usesFallback()) {
            throw new InvalidProfileException();
        }
        return resolved.theme();
    }

    public record StoredProfile(int version, ThemeTokens tokens) {}

    public static final class InvalidProfileException extends IOException {
        private InvalidProfileException() {
            this("Theme profile is invalid.");
        }

        private InvalidProfileException(String message) {
            super(message);
        }
    }
}
