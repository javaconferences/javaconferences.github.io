package org.javaconference.generator;

import io.yupiik.asciidoc.model.Body;
import io.yupiik.asciidoc.model.Document;
import io.yupiik.asciidoc.model.Element;
import io.yupiik.asciidoc.model.Link;
import io.yupiik.asciidoc.model.Paragraph;
import io.yupiik.asciidoc.model.Section;
import io.yupiik.asciidoc.model.Table;
import io.yupiik.asciidoc.model.Text;
import io.yupiik.asciidoc.parser.Parser;
import io.yupiik.asciidoc.parser.resolver.ContentResolver;
import io.yupiik.fusion.framework.api.container.Types;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.framework.build.api.json.JsonProperty;
import io.yupiik.fusion.framework.build.api.lifecycle.Destroy;
import io.yupiik.fusion.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Locale.ROOT;

/**
 * Parses the {@code README.adoc} source (AsciiDoc tables grouped by {@code == YYYY}
 * sections) and produces the full {@link Conference} list, tagging each conference
 * with the year of the section it lives in (the authoritative year, not parsed from
 * the free-text date column). Locations are geocoded through Nominatim, parsing the
 * response with {@link JsonMapper}.
 */
@ApplicationScoped
public class ConferenceReader implements AutoCloseable {
    private static final long DEFAULT_REQUEST_INTERVAL_MILLIS = 1_100L;
    private static final long MAX_BACKOFF_MILLIS = 10_000L;
    private static final int LOCATION_LOOKUP_ATTEMPTS = 6;
    private static final String STATIC_SEED_RESOURCE = "/locations.properties";
    static final Path DEFAULT_PERSISTED_CACHE = Path.of("locations-cache.properties");
    private static final Pattern YEAR_DIGITS = Pattern.compile("^\\d{4}$");
    private static final Pattern TITLE_DIGITS = Pattern.compile("[^0-9]");
    private static final List<String> MONTHS = List.of(
            "january", "february", "march", "april", "may", "june",
            "july", "august", "september", "october", "november", "december");

    private final Logger logger = Logger.getLogger(getClass().getSimpleName());
    private final JsonMapper json;
    private final ConcurrentLazyClient client;
    private final long requestIntervalMillis;
    private final Path persistedCachePath;

    public ConferenceReader(final JsonMapper json) {
        this(json, null, DEFAULT_REQUEST_INTERVAL_MILLIS, DEFAULT_PERSISTED_CACHE);
    }

    /**
     * All-options constructor, exposed {@code protected} so tests can wire every knob (an injected
     * client to stub retries, a short request interval, and a custom persisted-cache path) through a
     * single entry point instead of a proliferation of test-only overloads.
     *
     * @param json                the JSON mapper used to decode geocoder responses
     * @param injectedClient      an optional {@link HttpClient} to use instead of the lazy-built one
     * @param requestIntervalMillis the delay enforced between live lookups (pacing / rate limit)
     * @param persistedCachePath  where successful live lookups are stored for offline reuse
     */
    protected ConferenceReader(final JsonMapper json, final HttpClient injectedClient, final long requestIntervalMillis,
                               final Path persistedCachePath) {
        this.json = json;
        this.client = new ConcurrentLazyClient(injectedClient);
        this.requestIntervalMillis = requestIntervalMillis;
        this.persistedCachePath = persistedCachePath;
    }

    @Destroy
    public void close() {
        client.close();
    }

    private HttpClient client() {
        return client.get();
    }

    /** Lazy, close-once HttpClient so the executor/tls pools are only spun up on first geocode. */
    private static final class ConcurrentLazyClient {
        private volatile HttpClient value;
        private final boolean closeOnDestroy;

        private ConcurrentLazyClient() {
            this(null);
        }

        private ConcurrentLazyClient(final HttpClient injected) {
            this.closeOnDestroy = injected == null; // an injected client belongs to the test, never close it
            this.value = injected;
        }

        HttpClient get() {
            var current = value;
            if (current == null) {
                synchronized (this) {
                    current = value;
                    if (current == null) {
                        value = current = HttpClient.newBuilder()
                                .executor(java.util.concurrent.ForkJoinPool.commonPool())
                                .followRedirects(HttpClient.Redirect.ALWAYS)
                                .build();
                    }
                }
            }
            return current;
        }

        void close() {
            if (closeOnDestroy) {
                final var toClose = value;
                value = null;
                if (toClose != null) {
                    toClose.close();
                }
            }
        }
    }

    /**
     * Reads the given AsciiDoc file and returns every conference found, geocoding
     * the locations on the way (public Nominatim) while respecting its rate limit.
     *
     * @param source the README.adoc path
     */
    public Map<Integer, List<Conference>> read(final Path source) throws IOException {
        final var content = Files.readString(source, UTF_8);
        final var doc = new Parser().parse(content, new Parser.ParserContext(ContentResolver.of(source)));
        final var rows = collectRows(doc.body());
        final var locations = new LocationCache();
        final var grouped = new java.util.TreeMap<Integer, List<Conference>>();
        for (final var row : rows) {
            grouped.computeIfAbsent(row.year(), y -> new ArrayList<>()).add(toConference(row, locations));
        }
        grouped.replaceAll((k, v) -> List.copyOf(v));
        return Map.copyOf(grouped);
    }

    // --- AsciiDoc AST walking -------------------------------------------------

    /** Collects (year, rawCells) pairs from every table in the document. */
    private List<Row> collectRows(final Body body) {
        final var out = new ArrayList<Row>();
        walkSections(body.children(), null, out);
        return out;
    }

    private void walkSections(final List<Element> elements, final Integer inheritedYear, final List<Row> out) {
        for (final var element : elements) {
            if (element instanceof Section section) {
                final var year = sectionYear(section.title(), inheritedYear);
                walkSections(section.children(), year, out);
            } else if (inheritedYear != null && element instanceof Table table) {
                for (final var row : table.elements()) {
                    if (row.isEmpty() || isHeader(row)) {
                        continue;
                    }
                    if (row.size() != 5) {
                        // Defensive: a row whose cell count differs from the canonical 5-column layout
                        // (e.g. a stray extra column) would otherwise shift every subsequent column and
                        // geocode the conference name as if it were the location. Skip it and flag it.
                        logger.warning(() -> "Skipping row with unexpected cell count " + row.size() +
                                " (expected 5): [" +
                                row.stream().map(this::textOf).collect(java.util.stream.Collectors.joining(" | ")) + "]");
                        continue;
                    }
                    out.add(new Row(inheritedYear, row));
                }
            }
        }
    }

    private boolean isHeader(final List<Element> row) {
        final var first = textOf(row.get(0));
        return first.isBlank() || "Conference".equalsIgnoreCase(first);
    }

    /** Extracts the 4-digit year from a section title, else falls back to the parent year. */
    private Integer sectionYear(final Element title, final Integer inheritedYear) {
        final var digits = TITLE_DIGITS.matcher(textOf(title)).replaceAll("");
        if (YEAR_DIGITS.matcher(digits).matches()) {
            final var year = Integer.parseInt(digits);
            if (year >= 2000 && year <= 2100) {
                return year;
            }
        }
        return inheritedYear;
    }

    // --- Row -> Conference ---------------------------------------------------

    private Conference toConference(final Row row, final LocationCache locations) {
        final var cells = row.cells();
        final var locationName = cellText(cells.get(1));
        return new Conference(
                cellText(cells.get(0)),
                cellLink(cells.get(0)),
                locationName,
                locations.resolve(locationName),
                "yes".equalsIgnoreCase(cellText(cells.get(2))) || "true".equalsIgnoreCase(cellText(cells.get(2))),
                cellText(cells.get(3)),
                new Conference.CfP(parseCfpLink(cells.get(4)), parseCfpEndDate(cellText(cells.get(4)))));
    }

    private String parseCfpLink(final Element cell) {
        final var text = cellText(cell);
        if (text.isBlank() || "-".equals(text) || text.startsWith("Unconference") || text.startsWith("By invitation")
                || text.startsWith("TBA") || text.startsWith("Not open") || text.startsWith("Opens")) {
            return "";
        }
        return cellLink(cell);
    }

    private String parseCfpEndDate(final String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        final var lower = text.toLowerCase(ROOT);
        final int close = lower.indexOf("closed");
        if (close < 0) {
            return "";
        }
        final int pClose = lower.indexOf(')', close);
        if (pClose < 0) {
            return "";
        }
        final var after = text.substring(close + "closed".length(), pClose).strip();
        if (after.isBlank()) {
            return "";
        }
        for (final var month : MONTHS) {
            if (lower.contains(month)) {
                return after;
            }
        }
        return "";
    }

    // --- cell AST -> text / link ---------------------------------------------

    private String cellText(final Element cell) {
        final var sb = new StringBuilder();
        appendText(cell, sb);
        return sb.toString().strip();
    }

    private String textOf(final Element element) {
        if (element instanceof Text text) {
            return text.value();
        }
        return cellText(element);
    }

    private void appendText(final Element element, final StringBuilder sb) {
        if (element instanceof Text text) {
            sb.append(text.value());
        } else if (element instanceof Link link) {
            appendText(link.label(), sb);
        } else if (element instanceof Paragraph paragraph) {
            paragraph.children().forEach(it -> appendText(it, sb));
        }
    }

    private String cellLink(final Element cell) {
        final var ref = new AtomicReference<String>();
        appendLink(cell, ref);
        return ref.get() == null ? "" : ref.get();
    }

    private void appendLink(final Element element, final AtomicReference<String> ref) {
        if (ref.get() != null) {
            return;
        }
        if (element instanceof Link link) {
            ref.set(link.url());
        } else if (element instanceof Paragraph paragraph) {
            paragraph.children().forEach(it -> appendLink(it, ref));
        }
    }

    // --- helpers -------------------------------------------------------------

    private record Row(int year, List<Element> cells) {
    }

    @JsonModel
    record Address(String country, @JsonProperty("country_code") String countryCode) {
    }

    @JsonModel
    record GeoEntry(double lat, double lon, Address address,
                    @JsonProperty("display_name") String displayName) {
    }

    /** Geocoding cache + Nominatim lookup with retries, parsing JSON through JsonMapper. */
    private class LocationCache {
        private final Map<String, Coordinate> cache = new java.util.HashMap<>();
        private final Map<String, Coordinate> persisted = new java.util.HashMap<>();
        private final AtomicLong nextAllowedRequestNanos = new AtomicLong();
        private final long pacingIntervalNanos = requestIntervalMillis * 1_000_000L;
        private final java.lang.reflect.Type listOfGeoEntries = new Types.ParameterizedTypeImpl(List.class, GeoEntry.class);

        private LocationCache() {
            // Curated static seed is authoritative for well-known cities; the growing persisted cache
            // only supplements it (persisted first, then seed, so seed wins on a shared key).
            loadPersisted();
            cache.putAll(persisted);
            seedFromStaticResource();
        }

        Coordinate resolve(final String location) {
            if (location == null || location.isBlank()) {
                return new Coordinate(0, 0, "", "");
            }
            final var hit = cache.get(location);
            if (hit != null) {
                return hit;
            }
            final var found = find(location);
            cache.put(location, found);
            if (isUsable(found)) {
                persist(location, found);
            }
            return found;
        }

        private boolean isUsable(final Coordinate coordinate) {
            return coordinate.lat() != 0 || coordinate.lon() != 0;
        }

        private void seedFromStaticResource() {
            try (final var in = ConferenceReader.class.getResourceAsStream(STATIC_SEED_RESOURCE)) {
                if (in != null) {
                    final var props = new Properties();
                    // read as UTF-8 so accented city names (Málaga, São Paulo...) match exactly
                    props.load(new java.io.StringReader(new String(in.readAllBytes(), UTF_8)));
                    props.forEach((k, v) -> cache.put((String) k, parseCoordinate((String) v)));
                }
            } catch (final IOException e) {
                logger.warning(() -> "Cannot load static location seed '" + STATIC_SEED_RESOURCE + "': " + e.getMessage());
            }
        }

        private void loadPersisted() {
            if (persistedCachePath == null || !Files.isRegularFile(persistedCachePath)) {
                return;
            }
            try {
                final var props = new Properties();
                props.load(new java.io.StringReader(Files.readString(persistedCachePath, UTF_8)));
                props.forEach((k, v) -> persisted.put((String) k, parseCoordinate((String) v)));
            } catch (final IOException e) {
                logger.warning(() -> "Cannot load persisted location cache '" + persistedCachePath + "': " + e.getMessage());
            }
        }

        private void persist(final String name, final Coordinate coordinate) {
            persisted.put(name, coordinate);
            if (persistedCachePath == null) {
                return;
            }
            try {
                final var parent = persistedCachePath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                final var props = new Properties();
                persisted.forEach((k, v) -> props.setProperty(k, coordinateToString(v)));
                try (final OutputStream out = Files.newOutputStream(persistedCachePath)) {
                    props.store(out, "Growing geocoding cache; safe to delete, the static seed is authoritative.");
                }
            } catch (final IOException e) {
                logger.warning(() -> "Cannot persist location cache '" + persistedCachePath + "': " + e.getMessage());
            }
        }

        private String coordinateToString(final Coordinate c) {
            return c.lat() + "," + c.lon() + "," + c.countryName() + "," + c.countryCode();
        }

        private Coordinate parseCoordinate(final String raw) {
            final var parts = raw.split(",", -1);
            try {
                return new Coordinate(
                        Double.parseDouble(parts[0]),
                        Double.parseDouble(parts[1]),
                        parts.length > 2 ? parts[2] : "",
                        parts.length > 3 ? parts[3] : "");
            } catch (final NumberFormatException e) {
                logger.warning(() -> "Ignoring malformed seeded location '" + raw + "'");
                return null;
            }
        }

        private Coordinate find(final String name) {
            final int coordinateStart = name.indexOf('(');
            if (coordinateStart > 0) {
                final int coordinateEnd = name.indexOf(')', coordinateStart + 1);
                final int sep = name.indexOf(',', coordinateStart);
                final var endCity = name.lastIndexOf(' ', coordinateStart);
                if (coordinateEnd > sep && sep > coordinateStart && endCity > coordinateStart) {
                    final var citySep = name.indexOf(',');
                    if (citySep > 0) {
                        return new Coordinate(
                                Double.parseDouble(name.substring(coordinateStart + 1, sep).strip()),
                                Double.parseDouble(name.substring(sep + 1, coordinateEnd).strip()),
                                name.substring(citySep + 1, endCity).strip(),
                                "");
                    }
                }
            }
            if ("online".equalsIgnoreCase(name)) {
                return new Coordinate(0, 0, "Online", "");
            }
            Coordinate result = null;
            for (int attempt = 1; attempt <= LOCATION_LOOKUP_ATTEMPTS && !Thread.currentThread().isInterrupted(); attempt++) {
                try {
                    return lookup(name);
                } catch (final RateLimitedException e) {
                    // Nominatim throttles shared IPs (CI runners in particular): back off and try again.
                    // `Retry-After` (seconds) is honored when the server sends it, else exponential backoff.
                    if (attempt >= LOCATION_LOOKUP_ATTEMPTS || Thread.currentThread().isInterrupted()) {
                        final int attemptAfterFailures = attempt;
                        logger.warning(() -> "Lookup of '" + name + "' failed after " + attemptAfterFailures +
                                " attempt(s) (rate-limited), using empty coordinates: " + e.getMessage());
                        break;
                    }
                    final long retryIn = Math.min(attempt * requestIntervalMillis, MAX_BACKOFF_MILLIS);
                    final long waitMillis = Math.max(e.retryAfterSeconds() * 1_000L, retryIn);
                    sleepQuietly(Math.max(waitMillis - requestIntervalMillis, 1L));
                } catch (final IOException | InterruptedException e) {
                    if (Thread.currentThread().isInterrupted()) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    if (attempt >= LOCATION_LOOKUP_ATTEMPTS) {
                        logger.warning(() -> "Lookup of '" + name + "' failed, using empty coordinates: " + e.getMessage());
                        break;
                    }
                    sleepQuietly(Math.min(attempt * requestIntervalMillis, MAX_BACKOFF_MILLIS));
                } catch (final RuntimeException e) {
                    logger.warning(() -> "Lookup of '" + name + "' failed, using empty coordinates: " + e.getMessage());
                    break;
                }
            }
            return result == null ? new Coordinate(0, 0, name, "") : result;
        }

        private void sleepQuietly(final long millis) {
            try {
                Thread.sleep(millis);
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        private Coordinate lookup(final String name) throws IOException, InterruptedException {
            final var uri = URI.create("https://nominatim.openstreetmap.org/search?" +
                    "q=" + URLEncoder.encode(name, UTF_8) + "&format=jsonv2&addressdetails=1&limit=1");
            awaitPacingSlot();
            final var response = client().send(
                    HttpRequest.newBuilder().GET().uri(uri)
                            .timeout(Duration.ofMinutes(5))
                            .header("User-Agent", "javaconferences.org-site-generator/2.0 (+https://github.com/javaconferences/javaconferences.github.io)")
                            .header("accept-language", "en-EN,en")
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) {
                // Too Many Requests: public Nominatim throttles shared IPs; retryable with backoff.
                final var retryAfter = response.headers().firstValue("Retry-After")
                        .flatMap(this::parseSeconds)
                        .orElse(0L);
                throw new RateLimitedException(name, retryAfter);
            }
            if (response.statusCode() != 200) {
                throw new IllegalArgumentException("Invalid response for '" + name + "': " + response.statusCode());
            }
            return parseNominatim(name, response.body());
        }

        /** Serializes the whole batch through the public one-request-per-<em>N</em>-seconds limit. */
        private void awaitPacingSlot() throws InterruptedException {
            final var now = System.nanoTime();
            final var next = nextAllowedRequestNanos.get();
            final var waitNanos = next - now;
            if (waitNanos > 0) {
                final long waitMillis = (waitNanos / 1_000_000L) + 1L;
                Thread.sleep(waitMillis);
            }
            // Reserve the next slot relative to the *actual* request time if we were late, else from `now`.
            // Using max(now, next) + interval keeps spacing honest even after a long backoff sleep.
            nextAllowedRequestNanos.set(Math.max(now, System.nanoTime()) + pacingIntervalNanos);
        }

        private Optional<Long> parseSeconds(final String raw) {
            try {
                return Optional.of(Long.parseLong(raw.strip()));
            } catch (final NumberFormatException e) {
                return Optional.empty();
            }
        }

        private Coordinate parseNominatim(final String name, final String jsonBody) {
            final List<GeoEntry> results;
            try {
                results = json.fromBytes(listOfGeoEntries, jsonBody.getBytes(UTF_8));
            } catch (final IllegalStateException illegalStateException) {
                // Nominatim may answer with an empty/commented array or a non-array error body
                throw new IllegalArgumentException("Unparseable geocoding response for '" + name + "'", illegalStateException);
            }
            if (results.isEmpty()) {
                throw new IllegalArgumentException("No location found for '" + name + "'");
            }
            final var entry = results.get(0);
            final var address = entry.address();
            var country = address == null ? "" : address.country();
            if (country == null || country.isBlank()) {
                country = fallbackCountry(entry.displayName());
            }
            final var code = address == null ? "" : address.countryCode();
            return new Coordinate(entry.lat(), entry.lon(), country == null ? "" : country, code == null ? "" : code);
        }

        private String fallbackCountry(final String displayName) {
            if (displayName == null || displayName.isBlank()) {
                return "";
            }
            final var segments = displayName.split(",");
            return segments[segments.length - 1].strip();
        }
    }

    /** Nominatim answered with HTTP 429 (Too Many Requests); retryable with backoff. */
    private static final class RateLimitedException extends IOException {
        private final long retryAfterSeconds;

        private RateLimitedException(final String name, final long retryAfterSeconds) {
            super("Invalid response for '" + name + "': 429");
            this.retryAfterSeconds = retryAfterSeconds;
        }

        private long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }
}