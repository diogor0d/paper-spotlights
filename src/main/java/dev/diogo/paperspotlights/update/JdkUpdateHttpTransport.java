package dev.diogo.paperspotlights.update;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

/** Java {@link HttpClient}-based transport with automatic redirects disabled. */
public final class JdkUpdateHttpTransport implements UpdateHttpTransport {
    private final HttpClient client;

    public JdkUpdateHttpTransport(Duration connectTimeout) {
        connectTimeout = UpdateValidation.requirePositiveDuration(connectTimeout, "connectTimeout");
        client = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    JdkUpdateHttpTransport(HttpClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public Response execute(Request request) throws IOException, InterruptedException {
        Objects.requireNonNull(request, "request");
        HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri())
                .timeout(request.timeout())
                .GET();
        request.headers().forEach(builder::header);

        HttpResponse<InputStream> response = client.send(
                builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        return new Response(response.statusCode(), response.headers().map(), response.body());
    }
}
