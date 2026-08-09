package net.mcdgg.chat.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Fetch-once, keep-on-disk storage for everything the client pulls off the network.
 *
 * <p>Two rules shape it. Nothing here may ever run on the render thread or a server tick,
 * so every method blocks honestly and callers hand it to an executor. And a stale asset
 * always beats a missing one: if revalidation fails for any reason, the cached bytes are
 * returned and the failure is reported through {@link Result#stale()} rather than thrown.
 * An emote that is one release out of date is invisible to a player; an emote that vanished
 * because a CDN blipped is not.
 */
public final class AssetCache {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private final Path directory;
    private final HttpClient http;
    private final Duration revalidateAfter;

    public AssetCache(Path directory, Duration revalidateAfter) {
        this.directory = directory;
        this.revalidateAfter = revalidateAfter;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Blocks until {@code url} is available locally.
     *
     * @throws IOException only when there is no usable copy at all: a network failure with
     *                     something already cached succeeds and reports {@code stale}
     */
    public Result fetch(String url) throws IOException {
        Path body = pathFor(url, ".bin");
        Path meta = pathFor(url, ".meta");
        boolean cached = Files.isRegularFile(body);

        if (cached && !isDueForRevalidation(body)) {
            return new Result(Files.readAllBytes(body), false, false);
        }

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", DggCdn.USER_AGENT)
                .GET();
        String etag = cached ? readEtag(meta) : null;
        if (etag != null) {
            request.header("If-None-Match", etag);
        }

        try {
            HttpResponse<byte[]> response =
                    http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();

            if (status == 304 && cached) {
                touch(body);
                return new Result(Files.readAllBytes(body), false, false);
            }
            if (status / 100 == 2) {
                byte[] bytes = response.body();
                writeAtomically(body, bytes);
                response.headers().firstValue("ETag")
                        .ifPresentOrElse(tag -> writeQuietly(meta, tag), () -> deleteQuietly(meta));
                return new Result(bytes, true, false);
            }
            if (cached) {
                touch(body);
                return new Result(Files.readAllBytes(body), false, true);
            }
            throw new IOException("HTTP " + status + " for " + url);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (cached) {
                return new Result(Files.readAllBytes(body), false, true);
            }
            throw e instanceof IOException io ? io : new IOException("interrupted fetching " + url, e);
        }
    }

    /** The cached bytes if there are any, without touching the network. */
    public Optional<byte[]> cached(String url) {
        Path body = pathFor(url, ".bin");
        if (!Files.isRegularFile(body)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(body));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public String fetchText(String url) throws IOException {
        return new String(fetch(url).bytes(), StandardCharsets.UTF_8);
    }

    private boolean isDueForRevalidation(Path body) {
        if (revalidateAfter.isZero() || revalidateAfter.isNegative()) {
            return true;
        }
        try {
            return Files.getLastModifiedTime(body).toInstant()
                    .plus(revalidateAfter)
                    .isBefore(java.time.Instant.now());
        } catch (IOException e) {
            return true;
        }
    }

    /**
     * Filenames are a hash of the URL rather than the URL itself: destiny.gg's paths are
     * version-pinned and would otherwise collide across releases on case-insensitive
     * filesystems, and emote prefixes differ only by case in several places.
     */
    private Path pathFor(String url, String suffix) {
        return directory.resolve(sha256Hex(url).substring(0, 40) + suffix);
    }

    static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }

    private void writeAtomically(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(directory);
        Path temp = Path.of(target + ".part");
        Files.write(temp, bytes);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String readEtag(Path meta) {
        try {
            List<String> lines = Files.readAllLines(meta, StandardCharsets.UTF_8);
            return lines.isEmpty() || lines.get(0).isBlank() ? null : lines.get(0);
        } catch (IOException e) {
            return null;
        }
    }

    private void writeQuietly(Path meta, String content) {
        try {
            Files.createDirectories(directory);
            Files.writeString(meta, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // An unwritable cache is a slower client, not a broken one.
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            // As above.
        }
    }

    private static void touch(Path path) {
        try {
            Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.from(java.time.Instant.now()));
        } catch (IOException e) {
            // Only costs an earlier revalidation next time.
        }
    }

    /**
     * @param bytes   the asset
     * @param fresh   true when this call went to the network and got new content
     * @param stale   true when revalidation was attempted and failed, so these bytes are old
     */
    public record Result(byte[] bytes, boolean fresh, boolean stale) {

        public String text() {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /** Wraps {@link #fetch} for call sites that already handle failure by falling back. */
    public Optional<Result> tryFetch(String url) {
        try {
            return Optional.of(fetch(url));
        } catch (IOException | UncheckedIOException e) {
            return Optional.empty();
        }
    }
}
