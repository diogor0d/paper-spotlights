package dev.diogo.paperspotlights.update;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitHubReleaseUpdaterTest {
    private static final String ASSET_NAME = "PaperSpotlights.jar";
    private static final String PLUGIN_NAME = "PaperSpotlights";
    private static final String MAIN_CLASS = "dev.diogo.paperspotlights.PaperSpotlightsPlugin";
    private static final String API_VERSION = "26.2";
    private static final Executor DIRECT_EXECUTOR = Runnable::run;

    @TempDir
    Path temporaryDirectory;

    @Test
    void stagesOnlyAHashVerifiedNewerAssetThroughTheApiAssetEndpoint() throws Exception {
        byte[] assetBytes = pluginJar("1.1.0", API_VERSION);
        String digest = sha256(assetBytes);
        FakeTransport transport = new FakeTransport(
                response(200, releaseJson("v1.1.0", 42, assetBytes.length, digest)),
                response(200, Map.of("Content-Length", List.of(Integer.toString(assetBytes.length))), assetBytes));

        UpdateResult result;
        try (GitHubReleaseUpdater updater = updater(transport)) {
            result = updater.checkAndStageAsync(
                    SemVer.parseTag("v1.0.0"), temporaryDirectory).join();
        }

        UpdateResult.Staged staged = assertInstanceOf(UpdateResult.Staged.class, result);
        assertEquals(URI.create(
                "https://api.github.com/repos/diogo/paper-spotlights/releases/assets/42"),
                transport.requests().get(1).uri());
        assertEquals("application/octet-stream", transport.requests().get(1).headers().get("Accept"));
        assertEquals("2026-03-10", transport.requests().get(1).headers().get("X-GitHub-Api-Version"));
        assertEquals(digest, staged.sha256Hex());
        assertArrayEquals(assetBytes, Files.readAllBytes(temporaryDirectory.resolve(ASSET_NAME)));
    }

    @Test
    void rejectsDigestMismatchAndRemovesTemporaryFile() throws Exception {
        byte[] assetBytes = pluginJar("1.1.0", API_VERSION);
        FakeTransport transport = new FakeTransport(
                response(200, releaseJson("v1.1.0", 42, assetBytes.length, "0".repeat(64))),
                response(200, Map.of("Content-Length", List.of(Integer.toString(assetBytes.length))), assetBytes));

        UpdateResult result;
        try (GitHubReleaseUpdater updater = updater(transport)) {
            result = updater.checkAndStageAsync(
                    SemVer.parseTag("v1.0.0"), temporaryDirectory).join();
        }

        UpdateResult.Failed failed = assertInstanceOf(UpdateResult.Failed.class, result);
        assertEquals(UpdateFailure.DIGEST_MISMATCH, failed.failure());
        assertFalse(Files.exists(temporaryDirectory.resolve(ASSET_NAME)));
        try (Stream<Path> entries = Files.list(temporaryDirectory)) {
            assertTrue(entries.noneMatch(path -> path.getFileName().toString().endsWith(".part")));
        }
    }

    @Test
    void refusesAssetRedirectsOutsideOfficialGithubHosts() throws Exception {
        byte[] assetBytes = "bytes".getBytes(StandardCharsets.UTF_8);
        FakeTransport transport = new FakeTransport(
                response(200, releaseJson("v1.1.0", 42, assetBytes.length, sha256(assetBytes))),
                response(302, Map.of("Location", List.of("https://example.com/malware.jar")), new byte[0]));

        UpdateResult result;
        try (GitHubReleaseUpdater updater = updater(transport)) {
            result = updater.checkAndStageAsync(
                    SemVer.parseTag("v1.0.0"), temporaryDirectory).join();
        }

        UpdateResult.Failed failed = assertInstanceOf(UpdateResult.Failed.class, result);
        assertEquals(UpdateFailure.UNSAFE_REDIRECT, failed.failure());
        assertEquals(2, transport.requests().size());
        assertFalse(Files.exists(temporaryDirectory.resolve(ASSET_NAME)));
    }

    @Test
    void doesNotDownloadWhenLatestReleaseIsNotNewer() throws Exception {
        byte[] assetBytes = "unused".getBytes(StandardCharsets.UTF_8);
        FakeTransport transport = new FakeTransport(
                response(200, releaseJson("v1.0.0", 42, assetBytes.length, sha256(assetBytes))));

        UpdateResult result;
        try (GitHubReleaseUpdater updater = updater(transport)) {
            result = updater.checkAndStageAsync(
                    SemVer.parseTag("v1.0.0"), temporaryDirectory).join();
        }

        assertInstanceOf(UpdateResult.UpToDate.class, result);
        assertEquals(1, transport.requests().size());
        assertFalse(Files.exists(temporaryDirectory.resolve(ASSET_NAME)));
    }

    @Test
    void rejectsAReleaseFromAnotherPaperApiChannel() throws Exception {
        byte[] assetBytes = pluginJar("1.1.0", "26.3");
        FakeTransport transport = new FakeTransport(
                response(200, releaseJson("v1.1.0", 42, assetBytes.length, sha256(assetBytes))),
                response(200, Map.of("Content-Length", List.of(Integer.toString(assetBytes.length))), assetBytes));

        UpdateResult result;
        try (GitHubReleaseUpdater updater = updater(transport)) {
            result = updater.checkAndStageAsync(
                    SemVer.parseTag("v1.0.0"), temporaryDirectory).join();
        }

        UpdateResult.Failed failed = assertInstanceOf(UpdateResult.Failed.class, result);
        assertEquals(UpdateFailure.ARTIFACT_INCOMPATIBLE, failed.failure());
        assertFalse(Files.exists(temporaryDirectory.resolve(ASSET_NAME)));
    }

    @Test
    void rejectsAJarWithoutItsDeclaredMainClass() throws Exception {
        byte[] assetBytes = pluginJar("1.1.0", API_VERSION, false);
        FakeTransport transport = new FakeTransport(
                response(200, releaseJson("v1.1.0", 42, assetBytes.length, sha256(assetBytes))),
                response(200, Map.of("Content-Length", List.of(Integer.toString(assetBytes.length))), assetBytes));

        UpdateResult result;
        try (GitHubReleaseUpdater updater = updater(transport)) {
            result = updater.checkAndStageAsync(
                    SemVer.parseTag("v1.0.0"), temporaryDirectory).join();
        }

        UpdateResult.Failed failed = assertInstanceOf(UpdateResult.Failed.class, result);
        assertEquals(UpdateFailure.ARTIFACT_INVALID, failed.failure());
        assertFalse(Files.exists(temporaryDirectory.resolve(ASSET_NAME)));
    }

    @Test
    void followsAnAllowedGithubReleaseAssetRedirect() throws Exception {
        byte[] assetBytes = pluginJar("1.1.0", API_VERSION);
        URI assetCdn = URI.create(
                "https://release-assets.githubusercontent.com/github-production-release-asset/42"
                        + "?sp=r&sig=signed");
        FakeTransport transport = new FakeTransport(
                response(200, releaseJson("v1.1.0", 42, assetBytes.length, sha256(assetBytes))),
                response(302, Map.of("Location", List.of(assetCdn.toString())), new byte[0]),
                response(200, Map.of("Content-Length", List.of(Integer.toString(assetBytes.length))), assetBytes));

        UpdateResult result;
        try (GitHubReleaseUpdater updater = updater(transport)) {
            result = updater.checkAndStageAsync(
                    SemVer.parseTag("v1.0.0"), temporaryDirectory).join();
        }

        assertInstanceOf(UpdateResult.Staged.class, result);
        assertEquals(assetCdn, transport.requests().get(2).uri());
        assertEquals(3, transport.requests().size());
    }

    @Test
    void closeInterruptsAnOwnedInFlightRequest() throws Exception {
        byte[] assetBytes = pluginJar("1.1.0", API_VERSION);
        BlockingTransport transport = new BlockingTransport(
                response(200, releaseJson("v1.1.0", 42, assetBytes.length, sha256(assetBytes))));
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        GitHubReleaseUpdater updater = new GitHubReleaseUpdater(config(), transport, executor);
        try {
            CompletableFuture<UpdateResult> future = updater.checkAndStageAsync(
                    SemVer.parseTag("v1.0.0"), temporaryDirectory);
            assertTrue(transport.downloadStarted().await(2, TimeUnit.SECONDS));
            updater.close();

            UpdateResult.Failed failed = assertInstanceOf(
                    UpdateResult.Failed.class,
                    future.get(2, TimeUnit.SECONDS));
            assertEquals(UpdateFailure.INTERRUPTED, failed.failure());
            assertTrue(executor.isShutdown());
        } finally {
            updater.close();
        }
    }

    private static GitHubReleaseUpdater updater(FakeTransport transport) {
        return new GitHubReleaseUpdater(config(), transport, DIRECT_EXECUTOR);
    }

    private static UpdaterConfig config() {
        return new UpdaterConfig(
                "diogo",
                "paper-spotlights",
                ASSET_NAME,
                1024,
                PLUGIN_NAME,
                MAIN_CLASS,
                API_VERSION,
                Duration.ofSeconds(1),
                Duration.ofSeconds(2));
    }

    private static String releaseJson(String tag, long id, int size, String digest) {
        return """
                {"tag_name":"%s","assets":[
                  {"id":%d,"name":"%s","size":%d,"digest":"sha256:%s"}
                ]}
                """.formatted(tag, id, ASSET_NAME, size, digest);
    }

    private static UpdateHttpTransport.Response response(int status, String body) {
        return response(status, Map.of(), body.getBytes(StandardCharsets.UTF_8));
    }

    private static UpdateHttpTransport.Response response(
            int status, Map<String, List<String>> headers, byte[] body) {
        return new UpdateHttpTransport.Response(
                status, headers, new ByteArrayInputStream(body));
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static byte[] pluginJar(String version, String apiVersion) throws IOException {
        return pluginJar(version, apiVersion, true);
    }

    private static byte[] pluginJar(String version, String apiVersion, boolean includeMainClass)
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(output)) {
            jar.putNextEntry(new JarEntry("plugin.yml"));
            jar.write(("""
                    name: %s
                    version: '%s'
                    main: %s
                    api-version: '%s'
                    """).formatted(PLUGIN_NAME, version, MAIN_CLASS, apiVersion)
                    .getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
            if (includeMainClass) {
                jar.putNextEntry(new JarEntry(MAIN_CLASS.replace('.', '/') + ".class"));
                jar.write(new byte[]{(byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe});
                jar.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private static final class FakeTransport implements UpdateHttpTransport {
        private final Queue<Response> responses = new ArrayDeque<>();
        private final List<Request> requests = new ArrayList<>();

        private FakeTransport(Response... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public Response execute(Request request) throws IOException {
            requests.add(request);
            Response response = responses.poll();
            if (response == null) {
                throw new IOException("No scripted response");
            }
            return response;
        }

        private List<Request> requests() {
            return List.copyOf(requests);
        }
    }

    private static final class BlockingTransport implements UpdateHttpTransport {
        private final Response metadata;
        private final CountDownLatch downloadStarted = new CountDownLatch(1);
        private boolean metadataServed;

        private BlockingTransport(Response metadata) {
            this.metadata = metadata;
        }

        @Override
        public synchronized Response execute(Request request) throws InterruptedException {
            if (!metadataServed) {
                metadataServed = true;
                return metadata;
            }
            downloadStarted.countDown();
            new CountDownLatch(1).await();
            throw new AssertionError("The blocking request should be interrupted");
        }

        private CountDownLatch downloadStarted() {
            return downloadStarted;
        }
    }
}
