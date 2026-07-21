package dev.diogo.paperspotlights.update;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A deliberately small, strict JSON parser for bounded GitHub API responses. */
final class MiniJson {
    private static final int MAX_DEPTH = 64;
    private static final int MAX_NUMBER_LENGTH = 128;

    private final String input;
    private int position;

    private MiniJson(String input) {
        this.input = input;
    }

    static Object parse(String input) throws UpdaterException {
        if (input == null) {
            throw invalid("JSON input is null", 0);
        }
        MiniJson parser = new MiniJson(input);
        parser.skipWhitespace();
        Object value = parser.parseValue(0);
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw parser.invalidHere("Trailing content after JSON value");
        }
        return value;
    }

    private Object parseValue(int depth) throws UpdaterException {
        if (depth > MAX_DEPTH) {
            throw invalidHere("JSON nesting is too deep");
        }
        if (atEnd()) {
            throw invalidHere("Unexpected end of JSON input");
        }
        return switch (current()) {
            case '{' -> parseObject(depth);
            case '[' -> parseArray(depth);
            case '"' -> parseString();
            case 't' -> parseLiteral("true", Boolean.TRUE);
            case 'f' -> parseLiteral("false", Boolean.FALSE);
            case 'n' -> parseLiteral("null", null);
            default -> {
                if (current() == '-' || isDigit(current())) {
                    yield parseNumber();
                }
                throw invalidHere("Unexpected character in JSON input");
            }
        };
    }

    private Map<String, Object> parseObject(int depth) throws UpdaterException {
        position++;
        skipWhitespace();
        Map<String, Object> object = new LinkedHashMap<>();
        if (consume('}')) {
            return Collections.unmodifiableMap(object);
        }
        while (true) {
            if (atEnd() || current() != '"') {
                throw invalidHere("Expected a JSON object key");
            }
            String key = parseString();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            if (object.containsKey(key)) {
                throw invalidHere("Duplicate JSON object key");
            }
            object.put(key, parseValue(depth + 1));
            skipWhitespace();
            if (consume('}')) {
                return Collections.unmodifiableMap(object);
            }
            expect(',');
            skipWhitespace();
        }
    }

    private List<Object> parseArray(int depth) throws UpdaterException {
        position++;
        skipWhitespace();
        List<Object> array = new ArrayList<>();
        if (consume(']')) {
            return Collections.unmodifiableList(array);
        }
        while (true) {
            array.add(parseValue(depth + 1));
            skipWhitespace();
            if (consume(']')) {
                return Collections.unmodifiableList(new ArrayList<>(array));
            }
            expect(',');
            skipWhitespace();
        }
    }

    private String parseString() throws UpdaterException {
        expect('"');
        StringBuilder value = new StringBuilder();
        while (!atEnd()) {
            char character = input.charAt(position++);
            if (character == '"') {
                validateSurrogates(value);
                return value.toString();
            }
            if (character == '\\') {
                if (atEnd()) {
                    throw invalidHere("Incomplete JSON escape sequence");
                }
                char escape = input.charAt(position++);
                switch (escape) {
                    case '"', '\\', '/' -> value.append(escape);
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'u' -> value.append(parseUnicodeEscape());
                    default -> throw invalidHere("Invalid JSON escape sequence");
                }
            } else {
                if (character < 0x20) {
                    throw invalidHere("Unescaped control character in JSON string");
                }
                value.append(character);
            }
        }
        throw invalidHere("Unterminated JSON string");
    }

    private char parseUnicodeEscape() throws UpdaterException {
        if (input.length() - position < 4) {
            throw invalidHere("Incomplete JSON Unicode escape");
        }
        int value = 0;
        for (int index = 0; index < 4; index++) {
            int digit = Character.digit(input.charAt(position++), 16);
            if (digit < 0) {
                throw invalidHere("Invalid JSON Unicode escape");
            }
            value = (value << 4) | digit;
        }
        return (char) value;
    }

    private void validateSurrogates(StringBuilder value) throws UpdaterException {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw invalidHere("Unpaired high surrogate in JSON string");
                }
                index++;
            } else if (Character.isLowSurrogate(character)) {
                throw invalidHere("Unpaired low surrogate in JSON string");
            }
        }
    }

    private JsonNumber parseNumber() throws UpdaterException {
        int start = position;
        consume('-');
        if (atEnd()) {
            throw invalidHere("Incomplete JSON number");
        }

        if (consume('0')) {
            if (!atEnd() && isDigit(current())) {
                throw invalidHere("Leading zero in JSON number");
            }
        } else {
            if (!isNonZeroDigit(current())) {
                throw invalidHere("Invalid JSON number");
            }
            do {
                position++;
            } while (!atEnd() && isDigit(current()));
        }

        if (consume('.')) {
            requireDigit("Missing fraction digits in JSON number");
            while (!atEnd() && isDigit(current())) {
                position++;
            }
        }

        if (!atEnd() && (current() == 'e' || current() == 'E')) {
            position++;
            if (!atEnd() && (current() == '+' || current() == '-')) {
                position++;
            }
            requireDigit("Missing exponent digits in JSON number");
            while (!atEnd() && isDigit(current())) {
                position++;
            }
        }

        if (position - start > MAX_NUMBER_LENGTH) {
            throw invalidHere("JSON number is too long");
        }
        return new JsonNumber(input.substring(start, position));
    }

    private Object parseLiteral(String literal, Object value) throws UpdaterException {
        if (!input.regionMatches(position, literal, 0, literal.length())) {
            throw invalidHere("Invalid JSON literal");
        }
        position += literal.length();
        return value;
    }

    private void requireDigit(String message) throws UpdaterException {
        if (atEnd() || !isDigit(current())) {
            throw invalidHere(message);
        }
    }

    private void expect(char expected) throws UpdaterException {
        if (!consume(expected)) {
            throw invalidHere("Expected '" + expected + "'");
        }
    }

    private boolean consume(char expected) {
        if (!atEnd() && current() == expected) {
            position++;
            return true;
        }
        return false;
    }

    private void skipWhitespace() {
        while (!atEnd()) {
            char character = current();
            if (character != ' ' && character != '\t' && character != '\r' && character != '\n') {
                return;
            }
            position++;
        }
    }

    private char current() {
        return input.charAt(position);
    }

    private boolean atEnd() {
        return position >= input.length();
    }

    private static boolean isDigit(char character) {
        return character >= '0' && character <= '9';
    }

    private static boolean isNonZeroDigit(char character) {
        return character >= '1' && character <= '9';
    }

    private UpdaterException invalidHere(String message) {
        return invalid(message, position);
    }

    private static UpdaterException invalid(String message, int position) {
        return new UpdaterException(
                UpdateFailure.RELEASE_FORMAT_INVALID,
                message + " at character " + position);
    }

    record JsonNumber(String lexicalValue) {
    }
}
