package io.github.stevdrey.realtimevoicelab.realtime;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.EnumMap;
import java.util.Map;
import java.util.Properties;

import io.github.stevdrey.realtimevoicelab.realtime.SessionSettings.Model;

record SessionPricing(Map<Model, Rates> models, BigDecimal cutoff, boolean invalidCutoff) {
    private static final String DEFAULT_RATES = "realtime-pricing.properties";
    private static final BigDecimal SPEND_FRACTION = new BigDecimal("0.80");
    private static final int MAX_PRICING_BYTES = 8192;

    static SessionPricing configured() {
        return load(System.getenv("RVL_PRICING_FILE"), System.getenv("RVL_ESTIMATED_SPEND_LIMIT_USD"));
    }

    static SessionPricing load(String pricingFile, String limitUsd) {
        var limit = decimal(limitUsd);
        boolean invalid = limitUsd != null && (limit == null || limit.signum() == 0);
        var rates = new EnumMap<Model, Rates>(Model.class);
        try (var stream = openRates(pricingFile)) {
            var properties = readProperties(stream);
            for (var model : Model.values()) {
                var modelRates = readRates(properties, model.id() + ".");
                if (modelRates != null) {
                    rates.put(model, modelRates);
                }
            }
        } catch (IOException | IllegalArgumentException | SecurityException ignored) {
            // Missing or invalid local configuration means no verified prices.
        }
        return new SessionPricing(Map.copyOf(rates), invalid || limit == null ? null : limit.multiply(SPEND_FRACTION),
                invalid);
    }

    private static InputStream openRates(String pricingFile) throws IOException {
        if (pricingFile == null) {
            return SessionPricing.class.getResourceAsStream(DEFAULT_RATES);
        }
        var path = Path.of(pricingFile);
        return Files.isRegularFile(path) ? Files.newInputStream(path) : null;
    }

    private static Properties readProperties(InputStream stream) throws IOException {
        var properties = new Properties();
        if (stream != null) {
            var bytes = stream.readNBytes(MAX_PRICING_BYTES + 1);
            if (bytes.length <= MAX_PRICING_BYTES) {
                properties.load(new ByteArrayInputStream(bytes));
            }
        }
        return properties;
    }

    private static Rates readRates(Properties properties, String prefix) {
        try {
            var date = properties.getProperty(prefix + "date", "");
            var textInput = decimal(properties.getProperty(prefix + "text-input"));
            var cachedText = decimal(properties.getProperty(prefix + "cached-text-input"));
            var textOutput = decimal(properties.getProperty(prefix + "text-output"));
            var audioInput = decimal(properties.getProperty(prefix + "audio-input"));
            var cachedAudio = decimal(properties.getProperty(prefix + "cached-audio-input"));
            var audioOutput = decimal(properties.getProperty(prefix + "audio-output"));
            if (textInput == null || cachedText == null || textOutput == null
                    || audioInput == null || cachedAudio == null || audioOutput == null) {
                return null;
            }
            return new Rates(LocalDate.parse(date), textInput, cachedText, textOutput, audioInput, cachedAudio,
                    audioOutput);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static BigDecimal decimal(String value) {
        return value != null && value.matches("[0-9]{1,9}(\\.[0-9]{1,9})?") ? new BigDecimal(value) : null;
    }

    record Rates(LocalDate date, BigDecimal textInput, BigDecimal cachedText, BigDecimal textOutput,
                 BigDecimal audioInput, BigDecimal cachedAudio, BigDecimal audioOutput) {
        BigDecimal estimate(ResponseUsage usage) {
            return charge(textInput, usage.textInput() - usage.cachedText())
                    .add(charge(cachedText, usage.cachedText()))
                    .add(charge(textOutput, usage.textOutput()))
                    .add(charge(audioInput, usage.audioInput() - usage.cachedAudio()))
                    .add(charge(cachedAudio, usage.cachedAudio()))
                    .add(charge(audioOutput, usage.audioOutput()))
                    .movePointLeft(6);
        }

        private BigDecimal charge(BigDecimal rate, long tokens) {
            return rate.multiply(BigDecimal.valueOf(tokens));
        }
    }
}
