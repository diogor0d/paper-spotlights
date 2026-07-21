package dev.diogo.paperspotlights.update;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Checks a public GitHub repository's latest release and stages a verified asset.
 *
 * <p>This service has no Bukkit dependency and performs all work away from the
 * caller thread. Pass Paper's configured update directory as {@code updateFolder};
 * this class never discovers or replaces the running plugin JAR. A staged result
 * is intended for Paper to apply during a later server restart.</p>
 */
public final class GitHubReleaseUpdater implements AutoCloseable {
    private static final String API_HOST = "api.github.com";
    private static final int MAX_REDIRECTS = 5;
    private static final long MAX_METADATA_BYTES = 1024L * 1024L;
    private static final int COPY_BUFFER_BYTES = 16 * 1024;
    private static final Pattern CONTENT_LENGTH = Pattern.compile("[0-9]+");
    private static final Map<String, String> METADATA_HEADERS = Map.of(
            "Accept", "application/vnd.github+json",
            "X-GitHub-Api-Version", "2026-03-10",
            "User-Agent", "PaperSpotlights-Updater/1.0");
    private static final Map<String, String> ASSET_HEADERS = Map.of(
            "Accept", "application/octet-stream",
            "X-GitHub-Api-Version", "2026-03-10",
            "User-Agent", "PaperSpotlights-Updater/1.0");

    private final UpdaterConfig config;
    private final UpdateHttpTransport transport;
    private final Executor executor;
    private final ExecutorService ownedExecutor;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean running = new AtomicBoolean();

    /** Creates an updater backed by Java 25's {@code HttpClient} and virtual threads. */
    public GitHubReleaseUpdater(UpdaterConfig config) {
        this(config, createDefaultTransport(config), Executors.newVirtualThreadPerTaskExecutor());
    }

    GitHubReleaseUpdater(
            UpdaterConfig config,
            UpdateHttpTransport transport,
            ExecutorService ownedExecutor) {
        this.config = Objects.requireNonNull(config, "config");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.executor = Objects.requireNonNull(ownedExecutor, "ownedExecutor");
        this.ownedExecutor = ownedExecutor;
    }

    /**
     * Creates an updater with caller-owned transport and executor, useful for
     * integration and deterministic testing. Closing this updater does not close
     * the supplied objects.
     */
    public GitHubReleaseUpdater(
            UpdaterConfig config, UpdateHttpTransport transport, Executor executor) {
        this.config = Objects.requireNonNull(config, "config");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.ownedExecutor = null;
    }

    private static UpdateHttpTransport createDefaultTransport(UpdaterConfig config) {
        Objects.requireNonNull(config, "config");
        return new JdkUpdateHttpTransport(config.connectTimeout());
    }

    /**
     * Asynchronously checks the latest release and, only when newer, stages its
     * exact configured asset under {@code updateFolder}.
     */
    public CompletableFuture<UpdateResult> checkAndStageAsync(
            SemVer currentVersion, Path updateFolder) {
        Objects.requireNonNull(currentVersion, "currentVersion");
        Objects.requireNonNull(updateFolder, "updateFolder");

        if (closed.get()) {
            return CompletableFuture.completedFuture(failed(
                    UpdateFailure.CLOSED, "The updater is closed"));
        }
        if (!running.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(failed(
                    UpdateFailure.ALREADY_RUNNING, "An update check is already running"));
        }

        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return checkAndStage(currentVersion, updateFolder);
                } finally {
                    running.set(false);
                }
            }, executor);
        } catch (RejectedExecutionException exception) {
            running.set(false);
            return CompletableFuture.completedFuture(failed(
                    UpdateFailure.SCHEDULING_FAILED, "The update check could not be scheduled"));
        }
    }

    private UpdateResult checkAndStage(SemVer currentVersion, Path updateFolder) {
        try {
            GitHubRelease release = fetchLatestRelease();
            if (release.version().compareTo(currentVersion) <= 0) {
                return new UpdateResult.UpToDate(currentVersion, release);
            }
            return stageRelease(currentVersion, release, updateFolder);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return failed(UpdateFailure.INTERRUPTED, "The update check was interrupted");
        } catch (UpdaterException exception) {
            return failed(exception.failure(), exception.getMessage());
        } catch (RuntimeException exception) {
            return failed(UpdateFailure.INTERNAL_ERROR, "The updater encountered an internal error");
        }
    }

    private GitHubRelease fetchLatestRelease() throws UpdaterException, InterruptedException {
        URI releaseUri = URI.create("https://" + API_HOST + "/repos/"
                + config.owner() + "/" + config.repository() + "/releases/latest");
        UpdateHttpTransport.Response response;
        try {
            response = openFollowingRedirects(releaseUri, METADATA_HEADERS, false);
        } catch (IOException exception) {
            throw new UpdaterException(
                    UpdateFailure.RELEASE_REQUEST_FAILED,
                    "The GitHub release request failed",
                    exception);
        }

        try (response) {
            if (response.statusCode() != 200) {
                throw new UpdaterException(
                        UpdateFailure.RELEASE_RESPONSE_INVALID,
                        "GitHub returned HTTP " + response.statusCode() + " for release metadata");
            }
            OptionalLong contentLength = parseContentLength(
                    response, UpdateFailure.RELEASE_RESPONSE_INVALID, "release metadata");
            if (contentLength.isPresent() && contentLength.getAsLong() > MAX_METADATA_BYTES) {
                throw new UpdaterException(
                        UpdateFailure.RELEASE_RESPONSE_INVALID,
                        "GitHub release metadata exceeds the size limit");
            }

            byte[] body = readBounded(
                    response.body(),
                    MAX_METADATA_BYTES,
                    UpdateFailure.RELEASE_RESPONSE_INVALID,
                    "GitHub release metadata exceeds the size limit");
            if (contentLength.isPresent() && contentLength.getAsLong() != body.length) {
                throw new UpdaterException(
                        UpdateFailure.RELEASE_RESPONSE_INVALID,
                        "GitHub release metadata length does not match Content-Length");
            }
            return ReleaseJsonParser.parse(
                    decodeUtf8(body), config.assetName(), config.maxDownloadBytes());
        } catch (IOException exception) {
            throw new UpdaterException(
                    UpdateFailure.RELEASE_REQUEST_FAILED,
                    "The GitHub release response could not be read",
                    exception);
        }
    }

    private UpdateResult.Staged stageRelease(
            SemVer currentVersion, GitHubRelease release, Path updateFolder)
            throws UpdaterException, InterruptedException {
        Path folder = prepareUpdateFolder(updateFolder);
        Path target = folder.resolve(config.assetName()).normalize();
        if (!folder.equals(target.getParent())) {
            throw new UpdaterException(
                    UpdateFailure.FILESYSTEM_ERROR,
                    "The configured asset does not resolve inside the update directory");
        }
        if (Files.isSymbolicLink(target)) {
            throw new UpdaterException(
                    UpdateFailure.FILESYSTEM_ERROR,
                    "The update target must not be a symbolic link");
        }

        GitHubRelease.Asset asset = release.asset();
        URI assetUri = URI.create("https://" + API_HOST + "/repos/"
                + config.owner() + "/" + config.repository() + "/releases/assets/" + asset.id());
        UpdateHttpTransport.Response response;
        try {
            response = openFollowingRedirects(assetUri, ASSET_HEADERS, true);
        } catch (IOException exception) {
            throw new UpdaterException(
                    UpdateFailure.DOWNLOAD_REQUEST_FAILED,
                    "The GitHub asset request failed",
                    exception);
        }

        Path temporary = null;
        boolean responseClosed = false;
        boolean moved = false;
        try {
            if (response.statusCode() != 200) {
                throw new UpdaterException(
                        UpdateFailure.DOWNLOAD_RESPONSE_INVALID,
                        "GitHub returned HTTP " + response.statusCode() + " for the release asset");
            }

            OptionalLong contentLength = parseContentLength(
                    response, UpdateFailure.DOWNLOAD_RESPONSE_INVALID, "release asset");
            validateDeclaredSizes(asset.sizeBytes(), contentLength);

            try {
                temporary = Files.createTempFile(folder, ".paper-spotlights-", ".part");
            } catch (IOException exception) {
                throw new UpdaterException(
                        UpdateFailure.FILESYSTEM_ERROR,
                        "A temporary update file could not be created",
                        exception);
            }

            DownloadedAsset downloaded = streamAsset(response.body(), temporary);
            validateDownloadedAsset(downloaded, asset, contentLength);

            try {
                response.close();
                responseClosed = true;
            } catch (IOException exception) {
                throw new UpdaterException(
                        UpdateFailure.DOWNLOAD_REQUEST_FAILED,
                        "The GitHub asset response could not be closed cleanly",
                        exception);
            }

            PluginJarValidator.validate(temporary, release, config);

            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                moved = true;
            } catch (AtomicMoveNotSupportedException exception) {
                throw new UpdaterException(
                        UpdateFailure.ATOMIC_MOVE_UNSUPPORTED,
                        "The update directory does not support atomic moves",
                        exception);
            } catch (IOException exception) {
                throw new UpdaterException(
                        UpdateFailure.FILESYSTEM_ERROR,
                        "The verified update could not be moved into place",
                        exception);
            }

            return new UpdateResult.Staged(
                    currentVersion,
                    release,
                    target,
                    downloaded.bytes(),
                    downloaded.sha256Hex());
        } finally {
            if (!responseClosed) {
                closeQuietly(response);
            }
            if (!moved && temporary != null) {
                deleteQuietly(temporary);
            }
        }
    }

    private Path prepareUpdateFolder(Path updateFolder) throws UpdaterException {
        Path folder = updateFolder.toAbsolutePath().normalize();
        try {
            Files.createDirectories(folder);
        } catch (IOException exception) {
            throw new UpdaterException(
                    UpdateFailure.FILESYSTEM_ERROR,
                    "The update directory could not be created",
                    exception);
        }
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(folder)) {
            throw new UpdaterException(
                    UpdateFailure.FILESYSTEM_ERROR,
                    "The update path must be a real directory, not a symbolic link");
        }
        return folder;
    }

    private void validateDeclaredSizes(OptionalLong releaseSize, OptionalLong contentLength)
            throws UpdaterException {
        if (releaseSize.isPresent() && releaseSize.getAsLong() > config.maxDownloadBytes()) {
            throw tooLarge();
        }
        if (contentLength.isPresent() && contentLength.getAsLong() > config.maxDownloadBytes()) {
            throw tooLarge();
        }
        if (releaseSize.isPresent()
                && contentLength.isPresent()
                && releaseSize.getAsLong() != contentLength.getAsLong()) {
            throw new UpdaterException(
                    UpdateFailure.DOWNLOAD_RESPONSE_INVALID,
                    "The release asset size does not match Content-Length");
        }
    }

    private DownloadedAsset streamAsset(InputStream input, Path temporary)
            throws UpdaterException, InterruptedException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new UpdaterException(
                    UpdateFailure.INTERNAL_ERROR,
                    "SHA-256 is unavailable in this Java runtime",
                    exception);
        }

        long bytes = 0;
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        try (OutputStream output = Files.newOutputStream(
                temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Download interrupted");
                }

                int read;
                try {
                    read = input.read(buffer);
                } catch (IOException exception) {
                    throw new UpdaterException(
                            UpdateFailure.DOWNLOAD_REQUEST_FAILED,
                            "The GitHub asset response could not be read",
                            exception);
                }
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    continue;
                }
                if (read > config.maxDownloadBytes() - bytes) {
                    throw tooLarge();
                }

                try {
                    output.write(buffer, 0, read);
                } catch (IOException exception) {
                    throw new UpdaterException(
                            UpdateFailure.FILESYSTEM_ERROR,
                            "The temporary update file could not be written",
                            exception);
                }
                digest.update(buffer, 0, read);
                bytes += read;
            }
        } catch (IOException exception) {
            throw new UpdaterException(
                    UpdateFailure.FILESYSTEM_ERROR,
                    "The temporary update file could not be closed",
                    exception);
        }
        return new DownloadedAsset(bytes, digest.digest());
    }

    private void validateDownloadedAsset(
            DownloadedAsset downloaded,
            GitHubRelease.Asset asset,
            OptionalLong contentLength) throws UpdaterException {
        if (asset.sizeBytes().isPresent()
                && downloaded.bytes() != asset.sizeBytes().getAsLong()) {
            throw new UpdaterException(
                    UpdateFailure.DOWNLOAD_RESPONSE_INVALID,
                    "The downloaded asset size differs from the release metadata");
        }
        if (contentLength.isPresent() && downloaded.bytes() != contentLength.getAsLong()) {
            throw new UpdaterException(
                    UpdateFailure.DOWNLOAD_RESPONSE_INVALID,
                    "The downloaded asset size differs from Content-Length");
        }

        byte[] expectedDigest = HexFormat.of().parseHex(asset.sha256Hex());
        if (!MessageDigest.isEqual(expectedDigest, downloaded.sha256())) {
            throw new UpdaterException(
                    UpdateFailure.DIGEST_MISMATCH,
                    "The downloaded asset did not match its GitHub SHA-256 digest");
        }
    }

    private UpdateHttpTransport.Response openFollowingRedirects(
            URI initialUri, Map<String, String> headers, boolean assetRequest)
            throws IOException, InterruptedException, UpdaterException {
        URI currentUri = initialUri;
        int redirects = 0;
        while (true) {
            validateUri(currentUri, assetRequest);
            UpdateHttpTransport.Response response = transport.execute(
                    new UpdateHttpTransport.Request(currentUri, headers, config.requestTimeout()));
            if (!isRedirect(response.statusCode())) {
                return response;
            }

            List<String> locations = response.headerValues("Location");
            try {
                response.close();
            } catch (IOException exception) {
                throw new IOException("Redirect response could not be closed", exception);
            }
            if (locations.size() != 1) {
                throw new UpdaterException(
                        UpdateFailure.UNSAFE_REDIRECT,
                        "GitHub returned a redirect without one unambiguous Location header");
            }
            if (redirects >= MAX_REDIRECTS) {
                throw new UpdaterException(
                        UpdateFailure.UNSAFE_REDIRECT,
                        "GitHub returned too many redirects");
            }

            try {
                currentUri = currentUri.resolve(URI.create(locations.getFirst()));
            } catch (IllegalArgumentException exception) {
                throw new UpdaterException(
                        UpdateFailure.UNSAFE_REDIRECT,
                        "GitHub returned an invalid redirect Location",
                        exception);
            }
            redirects++;
        }
    }

    private static void validateUri(URI uri, boolean assetRequest) throws UpdaterException {
        String host = uri.getHost();
        if (uri.isOpaque()
                || !"https".equalsIgnoreCase(uri.getScheme())
                || host == null
                || uri.getUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)
                || uri.getFragment() != null
                || uri.getRawPath() == null
                || !uri.getRawPath().startsWith("/")) {
            throw unsafeUri();
        }

        String normalizedHost = host.toLowerCase(Locale.ROOT);
        boolean allowedHost = normalizedHost.equals(API_HOST);
        if (assetRequest) {
            allowedHost = allowedHost
                    || normalizedHost.equals("github.com")
                    || normalizedHost.equals("githubusercontent.com")
                    || normalizedHost.endsWith(".githubusercontent.com");
        } else if (uri.getRawQuery() != null) {
            throw unsafeUri();
        }
        if (!allowedHost) {
            throw unsafeUri();
        }
    }

    private static UpdaterException unsafeUri() {
        return new UpdaterException(
                UpdateFailure.UNSAFE_REDIRECT,
                "GitHub redirected the updater to a disallowed URL");
    }

    private static boolean isRedirect(int statusCode) {
        return statusCode == 301
                || statusCode == 302
                || statusCode == 303
                || statusCode == 307
                || statusCode == 308;
    }

    private static OptionalLong parseContentLength(
            UpdateHttpTransport.Response response,
            UpdateFailure failure,
            String description) throws UpdaterException {
        List<String> values = response.headerValues("Content-Length");
        if (values.isEmpty()) {
            return OptionalLong.empty();
        }
        if (values.size() != 1 || !CONTENT_LENGTH.matcher(values.getFirst()).matches()) {
            throw new UpdaterException(failure, "Invalid Content-Length for " + description);
        }
        try {
            return OptionalLong.of(Long.parseLong(values.getFirst()));
        } catch (NumberFormatException exception) {
            throw new UpdaterException(
                    failure, "Content-Length for " + description + " is too large", exception);
        }
    }

    private static byte[] readBounded(
            InputStream input, long maximum, UpdateFailure failure, String tooLargeMessage)
            throws IOException, InterruptedException, UpdaterException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long bytes = 0;
        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Response read interrupted");
            }
            int read = input.read(buffer);
            if (read < 0) {
                return output.toByteArray();
            }
            if (read == 0) {
                continue;
            }
            if (read > maximum - bytes) {
                throw new UpdaterException(failure, tooLargeMessage);
            }
            output.write(buffer, 0, read);
            bytes += read;
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
                    UpdateFailure.RELEASE_FORMAT_INVALID,
                    "GitHub release metadata is not valid UTF-8",
                    exception);
        }
    }

    private static UpdaterException tooLarge() {
        return new UpdaterException(
                UpdateFailure.DOWNLOAD_TOO_LARGE,
                "The release asset exceeds the configured download limit");
    }

    private static UpdateResult.Failed failed(UpdateFailure failure, String message) {
        return new UpdateResult.Failed(failure, message);
    }

    private static void closeQuietly(UpdateHttpTransport.Response response) {
        try {
            response.close();
        } catch (IOException ignored) {
            // The primary failure is more useful than a secondary close failure.
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best effort; the update target is never populated on this path.
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true) && ownedExecutor != null) {
            ownedExecutor.shutdownNow();
        }
    }

    private record DownloadedAsset(long bytes, byte[] sha256) {
        private DownloadedAsset {
            sha256 = sha256.clone();
        }

        @Override
        public byte[] sha256() {
            return sha256.clone();
        }

        private String sha256Hex() {
            return HexFormat.of().formatHex(sha256);
        }
    }
}
