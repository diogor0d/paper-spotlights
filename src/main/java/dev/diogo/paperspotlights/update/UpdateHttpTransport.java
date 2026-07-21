package dev.diogo.paperspotlights.update;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Minimal injectable HTTP boundary used by the updater. Implementations must not
 * follow redirects; redirect policy is enforced by {@link GitHubReleaseUpdater}.
 */
public interface UpdateHttpTransport {
    Response execute(Request request) throws IOException, InterruptedException;

    record Request(URI uri, Map<String, String> headers, Duration timeout) {
        public Request {
            Objects.requireNonNull(uri, "uri");
            Objects.requireNonNull(headers, "headers");
            timeout = UpdateValidation.requirePositiveDuration(timeout, "timeout");
            Map<String, String> copiedHeaders = new LinkedHashMap<>();
            for (Map.Entry<String, String> header : headers.entrySet()) {
                copiedHeaders.put(
                        Objects.requireNonNull(header.getKey(), "header name"),
                        Objects.requireNonNull(header.getValue(), "header value"));
            }
            headers = Collections.unmodifiableMap(copiedHeaders);
        }
    }

    record Response(int statusCode, Map<String, List<String>> headers, InputStream body)
            implements AutoCloseable {
        public Response {
            if (statusCode < 100 || statusCode > 599) {
                throw new IllegalArgumentException("Invalid HTTP status code");
            }
            Objects.requireNonNull(headers, "headers");
            Objects.requireNonNull(body, "body");

            Map<String, List<String>> copiedHeaders = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> header : headers.entrySet()) {
                String name = Objects.requireNonNull(header.getKey(), "header name");
                List<String> values = new ArrayList<>();
                for (String value : Objects.requireNonNull(header.getValue(), "header values")) {
                    values.add(Objects.requireNonNull(value, "header value"));
                }
                copiedHeaders.put(name, List.copyOf(values));
            }
            headers = Collections.unmodifiableMap(copiedHeaders);
        }

        public List<String> headerValues(String name) {
            Objects.requireNonNull(name, "name");
            List<String> values = new ArrayList<>();
            for (Map.Entry<String, List<String>> header : headers.entrySet()) {
                if (header.getKey().equalsIgnoreCase(name)) {
                    values.addAll(header.getValue());
                }
            }
            return List.copyOf(values);
        }

        @Override
        public void close() throws IOException {
            body.close();
        }
    }
}
