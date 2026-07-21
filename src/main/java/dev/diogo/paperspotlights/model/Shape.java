package dev.diogo.paperspotlights.model;

import java.util.Locale;

public enum Shape {
    CIRCLE,
    SQUARE;

    /**
     * Parses a shape name without regard to case or surrounding whitespace.
     *
     * @param value shape name
     * @return the parsed shape
     * @throws IllegalArgumentException when the value is null, blank, or unknown
     */
    public static Shape parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("shape must not be blank");
        }

        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown shape: " + value, exception);
        }
    }
}
