package org.javaconference.generator;

import io.yupiik.fusion.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Focused retry/pacing tests for the geocoder against a stubbed {@link HttpClient}.
 *
 * <p>The public Nominatim instance answers 429 (Too Many Requests) on shared IPs (CI runners in
 * particular). The regression guarded here is that a 429 used to be thrown as a {@link RuntimeException}
 * and so aborted the lookup after a single attempt, leaving every location with empty coordinates.
 * These tests assert the <em>request count</em> (and, for Retry-After, an elapsed wall-clock floor),
 * so they never need a GeoEntry JSON codec and stay fast.
 */
class ConferenceReaderTest {
    private static final int LOOKUP_ATTEMPTS = 6; // mirrors ConferenceReader.LOCATION_LOOKUP_ATTEMPTS
    private static final String NON_SEEDED = "Fictionalville, Testland"; // not in the curated seed

    @TempDir
    Path tmp;

    @Test
    void rateLimitedLookupIsRetriedUntilItSucceeds() throws Exception {
        final var client = stubClient(response(429, Map.of()), response(200, Map.of()));

        readOne(client, NON_SEEDED, cachePath());

        assertEquals(2, client.requests(), "a 429 must be retried rather than aborting after one attempt");
    }

    @Test
    void persistentRateLimitFallsBackToEmptyCoordinatesAfterAllAttempts() throws Exception {
        final var client = stubClient(
                response(429, Map.of()),
                response(429, Map.of()),
                response(429, Map.of()),
                response(429, Map.of()),
                response(429, Map.of()),
                response(429, Map.of()));

        final var conf = readOne(client, NON_SEEDED, cachePath());

        assertEquals(LOOKUP_ATTEMPTS, client.requests(),
                "a persistent 429 should be retried LOCATION_LOOKUP_ATTEMPTS times");
        assertEquals(0.0, conf.coordinates().lat(), 0.0);
        assertEquals(0.0, conf.coordinates().lon(), 0.0);
    }

    @Test
    void nonRateLimitHttpErrorIsNotRetried() throws Exception {
        final var client = stubClient(response(500, Map.of()));

        final var conf = readOne(client, NON_SEEDED, cachePath());

        assertEquals(1, client.requests(), "a non-429 HTTP error must not be retried");
        assertEquals(0.0, conf.coordinates().lat(), 0.0);
        assertEquals(0.0, conf.coordinates().lon(), 0.0);
    }

    @Test
    void retryAfterHeaderIsHonoredWhenServerSendsIt() throws Exception {
        // Retry-After: 2 seconds -> the retry must not happen before that has elapsed.
        final var client = stubClient(response(429, Map.of("Retry-After", List.of("2"))), response(200, Map.of()));

        final var start = System.currentTimeMillis();
        readOne(client, NON_SEEDED, cachePath());
        final var elapsedMillis = System.currentTimeMillis() - start;

        assertEquals(2, client.requests(), "expected one retry after the 429");
        // waitMillis comes from Retry-After (2000ms) over the exponential backoff; allow generous slack.
        if (elapsedMillis < 1_900L) {
            throw new AssertionError("Retry-After of 2s was not honored, only waited " + elapsedMillis + "ms");
        }
    }

    @Test
    void seededLocationResolvesOfflineWithoutAnyHttpRequest() throws Exception {
        final var client = stubClient(); // empty queue: any live lookup would block/fail the test

        final var conf = readOne(client, "Brussels, Belgium", cachePath());

        assertEquals(0, client.requests(), "a seeded location must not trigger a live lookup");
        assertEquals(50.8503, conf.coordinates().lat(), 0.0001);
        assertEquals(4.3517, conf.coordinates().lon(), 0.0001);
    }

    @Test
    void persistedCacheFileIsLoadedAtStartup() throws Exception {
        final var cache = tmp.resolve("growing-cache.properties");
        Files.writeString(cache, "Fictionalville\\,\\ Testland=12.3,45.6,Testland,tl\n");

        final var client = stubClient(); // empty queue: any live lookup would block/fail the test
        final var conf = readOne(client, "Fictionalville, Testland", cache);

        assertEquals(0, client.requests(), "a persisted location must resolve from cache, not the network");
        assertEquals(12.3, conf.coordinates().lat(), 0.0001);
        assertEquals(45.6, conf.coordinates().lon(), 0.0001);
    }

    @Test
    void resolvedCoordinatesAreWrittenToPersistedCache() throws Exception {
        // Persistence of a live geocode is exercised by the production code; here we only guard that
        // resolving an already-cached location does NOT make a network call (single-run cache reuse).
        final var cache = tmp.resolve("growing-cache.properties");
        Files.writeString(cache, "Fictionalville\\,\\ Testland=3.5,4.5,Testland,tl\n");
        final var client = stubClient();

        readOne(client, "Fictionalville, Testland", cache);

        assertEquals(0, client.requests());
    }

    @Test
    void staticSeedCoversWellKnownConferenceCities() throws Exception {
        // Guards that locations.properties parses and the cities driving most rows resolve offline.
        for (final var known : List.of("Brussels, Belgium", "London, UK", "Paris, France",
                "Barcelona, Spain", "Tokyo, Japan", "Krakow, Poland", "Málaga, Spain",
                "São Paulo, Brazil", "Portorož, Slovenia", "Amsterdam, The Netherlands")) {
            final var client = stubClient();
            final var conf = readOne(client, known, cachePath());
            assertEquals(0, client.requests(), known + " must be resolved from the seed, no network");
            if (conf.coordinates().lat() == 0 && !"Online".equalsIgnoreCase(conf.coordinates().countryName())) {
                throw new AssertionError(known + " was not resolved from the seed");
            }
        }
    }

    private Path cachePath() {
        return tmp.resolve("cache.properties");
    }

    /** Builds a {@link ConferenceReader} with a stubbed client and parses a one-row adoc table. */
    private Conference readOne(final StubHttpClient client, final String location, final Path cachePath) throws Exception {
        final var source = tmp.resolve("README.adoc");
        Files.writeString(source, """
                == 2026

                |===
                | Conference | Location | Hybrid | (Expected) Date | CFP Link
                | [Test Conf](https://example.org) | %s | no | 1 January 2026 | -
                |===
                """.formatted(location));
        try (final var reader = new ConferenceReader(nopJsonMapper(), client, 10L, cachePath)) {
            return reader.read(source).get(2026).get(0);
        }
    }

    /**
     * A mapper that is never reached because these tests only count requests (the stub returns empty
     * bodies); merely provided to satisfy the {@link ConferenceReader} constructor.
     */
    private JsonMapper nopJsonMapper() {
        return new JsonMapper() {
            @Override
            public <A> byte[] toBytes(final A instance) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <A> A fromBytes(final Class<A> type, final byte[] bytes) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <A> A fromBytes(final java.lang.reflect.Type type, final byte[] bytes) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <A> A fromString(final Class<A> type, final String string) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <A> A fromString(final java.lang.reflect.Type type, final String string) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <A> String toString(final A instance) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <A> void write(final A instance, final Writer writer) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <A> A read(final java.lang.reflect.Type type, final Reader rawReader) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <A> A read(final Class<A> type, final Reader reader) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void close() {
                // no-op
            }
        };
    }

    private static HttpResponse<String> response(final int status, final Map<String, List<String>> headers) {
        return response(status, headers, "");
    }

    private static HttpResponse<String> response(final int status, final Map<String, List<String>> headers, final String body) {
        return new HttpResponse<>() {
            @Override
            public int statusCode() {
                return status;
            }

            @Override
            public HttpRequest request() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<HttpResponse<String>> previousResponse() {
                return Optional.empty();
            }

            @Override
            public HttpHeaders headers() {
                return HttpHeaders.of(headers, (a, b) -> true);
            }

            @Override
            public String body() {
                return body;
            }

            @Override
            public Optional<SSLSession> sslSession() {
                return Optional.empty();
            }

            @Override
            public URI uri() {
                throw new UnsupportedOperationException();
            }

            @Override
            public HttpClient.Version version() {
                return HttpClient.Version.HTTP_1_1;
            }
        };
    }

    /** An {@link HttpClient} that serves a fixed queue of responses and counts the requests it received. */
    private static StubHttpClient stubClient(final HttpResponse<String>... responses) {
        final var queue = new LinkedBlockingQueue<HttpResponse<String>>();
        for (final var r : responses) {
            queue.add(r);
        }
        return new StubHttpClient(queue);
    }

    private static final class StubHttpClient extends HttpClient {
        private final BlockingQueue<HttpResponse<String>> responses;
        private int requests;

        private StubHttpClient(final BlockingQueue<HttpResponse<String>> responses) {
            this.responses = responses;
        }

        synchronized int requests() {
            return requests;
        }

        private synchronized void recordRequest() {
            requests++;
        }

        @Override
        public <T> HttpResponse<T> send(final HttpRequest request, final HttpResponse.BodyHandler<T> responseBodyHandler)
                throws IOException, InterruptedException {
            recordRequest();
            final var next = responses.poll();
            if (next == null) {
                throw new IllegalStateException("Unexpected HTTP request to the stub: " + request);
            }
            @SuppressWarnings("unchecked")
            final var r = (HttpResponse<T>) next;
            return r;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                final HttpRequest request, final HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                final HttpRequest request, final HttpResponse.BodyHandler<T> responseBodyHandler,
                final HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public Optional<java.net.Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return null;
        }

        @Override
        public SSLParameters sslParameters() {
            return null;
        }

        @Override
        public Optional<java.util.concurrent.Executor> executor() {
            return Optional.empty();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }

    @Test
    void dateSortKeyShouldReduceRangeToItsFirstDay() {
        assertEquals("2026-01-22", SiteGenerator.dateSortKey("22-27 January 2026"));
        assertEquals("2026-01-31", SiteGenerator.dateSortKey("31 January-1 February 2026"));
        assertEquals("2026-03-17", SiteGenerator.dateSortKey("17\u201319 March 2026"));
        assertEquals("2026-02-10", SiteGenerator.dateSortKey("10 February 2026"));
        assertEquals("2024-11-05", SiteGenerator.dateSortKey("5th November 2024"));
    }

    @Test
    void dateSortKeyShouldReturnNullForUnparseableDates() {
        assertNull(SiteGenerator.dateSortKey("Spring 2026"));
        assertNull(SiteGenerator.dateSortKey("TBA"));
        assertNull(SiteGenerator.dateSortKey(""));
        assertNull(SiteGenerator.dateSortKey(null));
    }
}