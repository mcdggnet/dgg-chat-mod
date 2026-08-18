package net.mcdgg.chat.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssetCacheTest {

    @TempDir
    Path cacheDir;

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile String body = "first";
    private volatile String etag = "\"v1\"";

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/asset";
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/asset", this::serve);
        server.setExecutor(null);
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void serve(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        String ifNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");
        if (etag.equals(ifNoneMatch)) {
            exchange.sendResponseHeaders(304, -1);
            exchange.close();
            return;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("ETag", etag);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test
    void firstFetchGoesToTheNetworkAndIsRemembered() throws IOException {
        AssetCache cache = new AssetCache(cacheDir, Duration.ofHours(1));

        AssetCache.Result first = cache.fetch(url());
        assertEquals("first", first.text());
        assertTrue(first.fresh());
        assertFalse(first.stale());
        assertEquals(1, requests.get());

        AssetCache.Result second = cache.fetch(url());
        assertEquals("first", second.text());
        assertFalse(second.fresh());
        assertEquals(1, requests.get(), "a warm entry inside its window must not hit the network");
    }

    @Test
    @DisplayName("an expired entry revalidates, and a 304 keeps the cached bytes")
    void revalidationWithEtag() throws IOException {
        new AssetCache(cacheDir, Duration.ofHours(1)).fetch(url());
        assertEquals(1, requests.get());

        AssetCache.Result revalidated = new AssetCache(cacheDir, Duration.ZERO).fetch(url());
        assertEquals(2, requests.get());
        assertEquals("first", revalidated.text());
        assertFalse(revalidated.fresh());
        assertFalse(revalidated.stale());
    }

    @Test
    void changedContentReplacesTheCachedCopy() throws IOException {
        AssetCache cache = new AssetCache(cacheDir, Duration.ZERO);
        assertEquals("first", cache.fetch(url()).text());

        body = "second";
        etag = "\"v2\"";
        AssetCache.Result updated = cache.fetch(url());
        assertEquals("second", updated.text());
        assertTrue(updated.fresh());
    }

    @Test
    @DisplayName("a stale emote beats a missing one, so a dead CDN falls back to disk")
    void networkFailureFallsBackToCache() throws IOException {
        AssetCache cache = new AssetCache(cacheDir, Duration.ZERO);
        String url = url();
        assertEquals("first", cache.fetch(url).text());

        server.stop(0);
        AssetCache.Result offline = cache.fetch(url);
        assertEquals("first", offline.text());
        assertTrue(offline.stale());
        assertFalse(offline.fresh());
    }

    @Test
    void anErrorStatusWithNothingCachedFails() {
        AssetCache cache = new AssetCache(cacheDir, Duration.ZERO);
        String missing = "http://127.0.0.1:" + server.getAddress().getPort() + "/nope";
        assertThrows(IOException.class, () -> cache.fetch(missing));
        assertTrue(cache.tryFetch(missing).isEmpty());
        assertTrue(cache.cached(missing).isEmpty());
    }

    @Test
    void cachedReadsDiskWithoutTouchingTheNetwork() throws IOException {
        AssetCache cache = new AssetCache(cacheDir, Duration.ofHours(1));
        cache.fetch(url());
        int before = requests.get();

        assertEquals("first", new String(cache.cached(url()).orElseThrow(), StandardCharsets.UTF_8));
        assertEquals(before, requests.get());
    }

    @Test
    @DisplayName("cache filenames are hashed, so URLs differing only in case cannot collide")
    void distinctKeysPerUrl() {
        assertEquals(64, AssetCache.sha256Hex("x").length());
        org.junit.jupiter.api.Assertions.assertNotEquals(
                AssetCache.sha256Hex("https://cdn.destiny.gg/emotes/PEPE.png"),
                AssetCache.sha256Hex("https://cdn.destiny.gg/emotes/pepe.png"));
    }

    @Test
    void textConvenienceMatchesBytes() throws IOException {
        AssetCache cache = new AssetCache(cacheDir, Duration.ofHours(1));
        assertEquals("first", cache.fetchText(url()));
    }

    @Test
    @DisplayName("rejected fresh content must not destroy the last-good cached copy")
    void rejectedDownloadKeepsTheCachedCopy() throws IOException {
        AssetCache cache = new AssetCache(cacheDir, Duration.ZERO);
        assertEquals("first", cache.fetch(url()).text());

        // The server now serves something this client cannot read (a manifest
        // format bump, say). The check throws; the cached copy must survive.
        body = "unreadable-v2";
        etag = "\"v2\"";
        AssetCache.Result result = cache.fetch(url(), bytes -> {
            if (new String(bytes, StandardCharsets.UTF_8).startsWith("unreadable")) {
                throw new IllegalArgumentException("version from the future");
            }
        });
        assertEquals("first", result.text(), "the cached copy is the one served");
        assertTrue(result.stale());
        assertFalse(result.fresh());

        assertEquals("first", new String(cache.cached(url()).orElseThrow(), StandardCharsets.UTF_8),
                "the cached copy is still on disk, not overwritten by the rejected body");
    }

    @Test
    void rejectedDownloadWithNothingCachedPropagates() {
        AssetCache cache = new AssetCache(cacheDir, Duration.ZERO);
        assertThrows(IOException.class, () -> cache.fetch(url(), bytes -> {
            throw new IllegalArgumentException("unreadable");
        }));
        assertTrue(cache.cached(url()).isEmpty(), "the rejected body must not be cached");
    }
}
