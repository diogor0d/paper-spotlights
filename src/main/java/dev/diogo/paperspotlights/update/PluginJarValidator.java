package dev.diogo.paperspotlights.update;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strictly checks the identity and Paper API channel of a downloaded plugin JAR. */
final class PluginJarValidator {
    private static final int MAX_PLUGIN_YML_BYTES = 64 * 1024;
    private static final Pattern FIELD = Pattern.compile(
            "^(name|version|main|api-version):[ \\t]*(.*?)[ \\t]*$");

    private PluginJarValidator() {
    }

    static void validate(Path path, GitHubRelease release, UpdaterConfig config)
            throws UpdaterException {
        Map<String, String> fields;
        try (JarFile jar = new JarFile(path.toFile(), false)) {
            if (jar.getJarEntry("paper-plugin.yml") != null) {
                throw invalid("The downloaded JAR contains an unexpected paper-plugin.yml descriptor");
            }
            JarEntry descriptor = jar.getJarEntry("plugin.yml");
            if (descriptor == null || descriptor.isDirectory()) {
                throw invalid("The downloaded JAR has no plugin.yml descriptor");
            }
            if (descriptor.getSize() > MAX_PLUGIN_YML_BYTES) {
                throw invalid("The downloaded JAR's plugin.yml is too large");
            }
            try (InputStream input = jar.getInputStream(descriptor)) {
                fields = parseDescriptor(decodeUtf8(readBounded(input)));
            }
            validateMainClass(jar, config.expectedMainClass());
        } catch (UpdaterException exception) {
            throw exception;
        } catch (IOException | SecurityException exception) {
            throw new UpdaterException(
                    UpdateFailure.ARTIFACT_INVALID,
                    "The downloaded asset is not a readable plugin JAR",
                    exception);
        }

        requireEqual(fields, "name", config.expectedPluginName());
        requireEqual(fields, "main", config.expectedMainClass());

        String releaseVersion = release.version().tag().substring(1);
        if (!releaseVersion.equals(fields.get("version"))) {
            throw invalid("The downloaded plugin version does not match the GitHub release tag");
        }
        if (!config.expectedApiVersion().equals(fields.get("api-version"))) {
            throw new UpdaterException(
                    UpdateFailure.ARTIFACT_INCOMPATIBLE,
                    "The downloaded plugin targets Paper API " + fields.get("api-version")
                            + ", but this installation follows the "
                            + config.expectedApiVersion() + " update channel");
        }
    }

    private static void validateMainClass(JarFile jar, String mainClass)
            throws IOException, UpdaterException {
        String entryName = mainClass.replace('.', '/') + ".class";
        JarEntry entry = jar.getJarEntry(entryName);
        if (entry == null || entry.isDirectory()) {
            throw invalid("The downloaded JAR does not contain its declared main class");
        }
        try (InputStream input = jar.getInputStream(entry)) {
            byte[] magic = input.readNBytes(4);
            if (magic.length != 4
                    || magic[0] != (byte) 0xca
                    || magic[1] != (byte) 0xfe
                    || magic[2] != (byte) 0xba
                    || magic[3] != (byte) 0xbe) {
                throw invalid("The downloaded JAR's declared main class is not a class file");
            }
        }
    }

    private static Map<String, String> parseDescriptor(String yaml) throws UpdaterException {
        Map<String, String> result = new HashMap<>();
        for (String line : yaml.split("\\R", -1)) {
            Matcher matcher = FIELD.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            String key = matcher.group(1);
            String value = unquote(matcher.group(2));
            if (value.isBlank() || result.put(key, value) != null) {
                throw invalid("The downloaded JAR has an invalid plugin.yml descriptor");
            }
        }
        for (String required : new String[]{"name", "version", "main", "api-version"}) {
            if (!result.containsKey(required)) {
                throw invalid("The downloaded JAR's plugin.yml is missing " + required);
            }
        }
        return Map.copyOf(result);
    }

    private static String unquote(String value) throws UpdaterException {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                return value.substring(1, value.length() - 1);
            }
        }
        if (value.indexOf('#') >= 0 || value.indexOf('\0') >= 0) {
            throw invalid("The downloaded JAR has an unsupported plugin.yml scalar");
        }
        return value;
    }

    private static byte[] readBounded(InputStream input) throws IOException, UpdaterException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int total = 0;
        while (true) {
            int read = input.read(buffer);
            if (read < 0) {
                return output.toByteArray();
            }
            if (read == 0) {
                continue;
            }
            if (read > MAX_PLUGIN_YML_BYTES - total) {
                throw invalid("The downloaded JAR's plugin.yml is too large");
            }
            output.write(buffer, 0, read);
            total += read;
        }
    }

    private static String decodeUtf8(byte[] bytes) throws UpdaterException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new UpdaterException(
                    UpdateFailure.ARTIFACT_INVALID,
                    "The downloaded JAR's plugin.yml is not valid UTF-8",
                    exception);
        }
    }

    private static void requireEqual(
            Map<String, String> fields,
            String key,
            String expected
    ) throws UpdaterException {
        if (!expected.equals(fields.get(key))) {
            throw invalid("The downloaded plugin " + key + " does not match this installation");
        }
    }

    private static UpdaterException invalid(String message) {
        return new UpdaterException(UpdateFailure.ARTIFACT_INVALID, message);
    }
}
