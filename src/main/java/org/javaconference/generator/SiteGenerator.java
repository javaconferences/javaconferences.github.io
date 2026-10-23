package org.javaconference.generator;

import io.yupiik.asciidoc.parser.Parser;
import io.yupiik.asciidoc.parser.resolver.ContentResolver;
import io.yupiik.asciidoc.renderer.html.AsciidoctorLikeHtmlRenderer;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.scanning.Bean;
import io.yupiik.fusion.framework.handlebars.HandlebarsCompiler;
import io.yupiik.fusion.framework.handlebars.spi.Template;
import io.yupiik.fusion.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Year;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;

/**
 * Renders the static site + JSON API into the configured output directory.
 *
 * <p>index.html shows the current and next year only; every older year gets its own
 * archive page. The JSON API keeps the legacy {@code conferences.json} and adds
 * {@code api/conferences/latest.json} and {@code api/conferences/{year}.json}.
 *
 * <p>The page shell, styles and filter UI live in {@code src/main/resources/templates/base.html}
 * (Handlebars) and are rendered with {@link HandlebarsCompiler}; the per-year body is
 * produced by {@code AsciidoctorLikeHtmlRenderer}.
 */
@Bean
@ApplicationScoped
public class SiteGenerator {
    private static final String SWAGGER_UI_VERSION = "5.31.0";
    private static final String SITE_BASE = "https://javaconferences.org";
    private static final String CUSTOM_DOMAIN = "javaconferences.org";
    private static final String SOURCE_BASE = "https://github.com/javaconferences/javaconferences.github.io";

    /** English descriptions injected into the OpenAPI component schemas (see {@link #applySchemaDocumentation}). */
    private static final Map<String, String> COMPONENT_DOCS = Map.of(
            "Conference", "A Java developer conference edition listed on javaconferences.org.",
            "CfP", "Call-for-papers details for a conference.",
            "Coordinate", "Geolocation of the conference venue, resolved from the city.");

    private final Logger logger = Logger.getLogger(getClass().getSimpleName());
    private final JsonMapper mapper;
    private final ConferenceReader conferenceReader;
    private Path source;
    private final Path schemaJson;
    private final HandlebarsCompiler handlebars = new HandlebarsCompiler();
    private Template pageTemplate;

    private Map<Integer, List<Conference>> byYear;
    private Countries countries;

    public SiteGenerator(final JsonMapper mapper, final ConferenceReader conferenceReader) {
        this.mapper = mapper;
        this.conferenceReader = conferenceReader;
        this.schemaJson = Path.of("target/classes/META-INF/fusion/json/schemas.json");
    }

    public void generate(final Path source, final Path output) throws Exception {
        this.source = source;
        byYear = conferenceReader.read(source);
        countries = new Countries();
        final int total = byYear.values().stream().mapToInt(List::size).sum();
        logger.info(() -> "Found #" + total + " conferences across " + byYear.size() + " years.");

        final var target = Files.createDirectories(output);
        final var yearChunks = splitByYear(Files.readString(source));
        pageTemplate = compileTemplate();

        Files.writeString(target.resolve(".nojekyll"), "");
        Files.writeString(target.resolve("CNAME"), CUSTOM_DOMAIN);
        Files.writeString(target.resolve("index.html"), index(yearChunks));
        Files.writeString(target.resolve("map.html"), map());
        writeApi(target);
        for (final var entry : byYear.entrySet()) {
            if (entry.getKey() < Year.now().getValue()) {
                Files.writeString(target.resolve(entry.getKey() + ".html"), archivePage(entry.getKey(), yearChunks.get(entry.getKey())));
            }
        }
        logger.info(() -> "Generation successful.");
    }

    // --- year chunking + AsciiDoc rendering -----------------------------------

    /** Splits the Adoc source into {@code year -> raw section} chunks (level-1 sections with a 4-digit title). */
    private Map<Integer, String> splitByYear(final String content) {
        final var lines = content.split("\n", -1);
        final var out = new LinkedHashMap<Integer, StringBuilder>();
        Integer current = null;
        for (final var line : lines) {
            final var trimmed = line.strip();
            if (trimmed.matches("== *\\d{4}")) {
                current = Integer.parseInt(trimmed.replaceAll("[^0-9]", ""));
                out.put(current, new StringBuilder());
            } else if (current != null && !trimmed.startsWith("==")) {
                out.get(current).append(line).append('\n');
            }
        }
        final var map = new LinkedHashMap<Integer, String>();
        out.forEach((k, v) -> map.put(k, v.toString()));
        return map;
    }

    /** Renders an AsciiDoc source fragment to an HTML body via asciidoc-java. */
    private String renderAdoc(final String ascii) {
        final var parser = new Parser();
        final var doc = parser.parse(ascii, new Parser.ParserContext(ContentResolver.of(source)));
        final var renderer = new AsciidoctorLikeHtmlRenderer();
        renderer.visit(doc);
        return renderer.result();
    }

    // --- Handlebars rendering -------------------------------------------------

    private Template compileTemplate() throws IOException {
        try (final var in = SiteGenerator.class.getResourceAsStream("/templates/base.html")) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath template /templates/base.html");
            }
            final var content = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            return handlebars.compile(new HandlebarsCompiler.CompilationContext(content));
        }
    }

    private String renderPage(final String title, final String contentHtml, final Map<String, Object> extra) {
        final var model = new HashMap<String, Object>();
        model.put("title", title);
        model.put("pageTitle", title.replace(" Java Conferences", ""));
        model.put("stats", heroStats());
        model.put("content", contentHtml);
        model.put("countries", countriesList());
        model.put("year", Year.now().getValue());
        model.put("siteBase", SITE_BASE);
        model.put("source", SOURCE_BASE);
        model.put("apiDocs", "api/index.html");
        model.put("mapOnly", false);
        model.putAll(extra == null ? Map.of() : extra);
        return pageTemplate.render(model);
    }

    /** Hero statistics: editions listed, countries and CFPs open, over the current + next year. */
    private List<Map<String, String>> heroStats() {
        final var thisYear = Year.now().getValue();
        return heroStats(pageContext(thisYear, thisYear + 1));
    }

    private List<String> countriesList() {
        return byYear.values().stream()
                .flatMap(List::stream)
                .map(c -> c.coordinates() == null ? "" : c.coordinates().countryName())
                .filter(it -> it != null && !it.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

    // --- pages ----------------------------------------------------------------

    /** Contextual hero stats: editions, countries and CFPs still open, counted over the given conferences. */
    private List<Map<String, String>> heroStats(final List<Conference> confs) {
        final long countries = confs.stream()
                .map(c -> c.coordinates() == null ? null : c.coordinates().countryName())
                .filter(n -> n != null && !n.isBlank() && !"online".equalsIgnoreCase(n))
                .distinct()
                .count();
        final long cfpOpen = confs.stream()
                .filter(c -> c.cfp() != null && c.cfp().link() != null && !c.cfp().link().isBlank())
                .count();
        return List.of(
                Map.of("key", "events", "value", Integer.toString(confs.size()), "label", "events"),
                Map.of("key", "countries", "value", Long.toString(countries), "label", "countries"),
                Map.of("key", "cfp", "value", Long.toString(cfpOpen), "label", "CFP open"));
    }

    /** All editions shown on a page, used for its contextual hero stats. */
    private List<Conference> pageContext(final int... years) {
        final var out = new ArrayList<Conference>();
        for (final var year : years) {
            out.addAll(byYear.getOrDefault(year, List.of()));
        }
        return List.copyOf(out);
    }

    /** Contextual counters as a JSON object: events, countries (online excluded) and CFPs open. */
    private String contextStatsJson(final List<Conference> confs) {
        final long countries = confs.stream()
                .map(c -> c.coordinates() == null ? null : c.coordinates().countryName())
                .filter(n -> n != null && !n.isBlank() && !"online".equalsIgnoreCase(n))
                .distinct()
                .count();
        final long cfpOpen = confs.stream()
                .filter(c -> c.cfp() != null && c.cfp().link() != null && !c.cfp().link().isBlank())
                .count();
        return "{\"events\":" + confs.size() + ",\"countries\":" + countries + ",\"cfp\":" + cfpOpen + "}";
    }

    private String index(final Map<Integer, String> yearChunks) {
        final var thisYear = Year.now().getValue();
        final var years = new TreeMap<Integer, String>(yearChunks);

        final var sb = new StringBuilder();
        final var nav = new StringBuilder();
        years.keySet().stream()
                .filter(y -> y < thisYear)
                .forEach(y -> nav.append("<li><a href=\"").append(y).append(".html\">").append(y).append("</a></li>"));

        for (final var year : List.of(thisYear, thisYear + 1)) {
            final var html = years.get(year);
            if (html != null) {
                sb.append(yearSection(year, injectCountryColumn(year, renderAdoc(html))));
            }
        }
        if (nav.length() > 0) {
            sb.append("<section class=\"archives\" id=\"archive\"><h2>Archive</h2><p>Previous years:</p><ul>")
                    .append(nav).append("</ul></section>");
        }
        sb.append("<p class=\"map-link\">View the <a href=\"map.html\">map</a> of current conferences.</p>");

        return renderPage("Java Conferences",
                sb.toString(),
                Map.of("subtitle", "Upcoming Java conferences for " + thisYear + " and " + (thisYear + 1) + "."));
    }

    private String archivePage(final int year, final String chunk) {
        final var now = Year.now().getValue();
        final var sb = new StringBuilder();
        if (chunk != null) {
            sb.append(yearSection(year, injectCountryColumn(year, renderAdoc(chunk))));
        } else {
            sb.append("<p>No conferences recorded for ").append(year).append(".</p>");
        }
        final var chips = new StringBuilder();
        byYear.keySet().stream()
                .filter(y -> y < now)
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(y ->
                        chips.append("<a class=\"archive-chip\" href=\"").append(y).append(".html\"")
                                .append(y == year ? " aria-current=\"true\"" : "").append(">").append(y).append("</a>"));
        sb.append("<nav class=\"archive-nav\">").append(chips).append("</nav>");

        return renderPage("Java Conferences " + year,
                sb.toString(),
                Map.of("subtitle", "Archived conferences from " + year + ".",
                        "stats", heroStats(pageContext(year)),
                        "archived", true,
                        "crumbs", "<a href=\"index.html\">&larr; Current</a> · <a href=\"index.html#archive\">Archive</a>"));
    }

    private String map() {
        final var confs = filterCurrent();
        final var sb = new StringBuilder();
        sb.append("<div class=\"map-controls\"><label for=\"map-year\">Year</label>")
                .append("<select id=\"map-year\" aria-label=\"Select the year to display on the map\">");
        final int now = Year.now().getValue();
        sb.append("<option value=\"").append(now).append("+").append(now + 1)
                .append("\" selected>").append(now).append(" + ").append(now + 1).append("</option>");
        byYear.keySet().stream()
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(y -> sb.append("<option value=\"").append(y).append("\">").append(y).append("</option>"));
        sb.append("</select></div>");
        sb.append("<div id=\"map\"></div>");
        sb.append("<script src=\"https://unpkg.com/leaflet@1.8.0/dist/leaflet.js\" integrity=\"sha512-BB3hKbKWOc9Ez/TAwyWxNXeoV9c1v6FIeYiBieIWkpLjauysF18NzgR1MBNBXf8/KABdlkX68nAhlwcDFLGPCQ==\" crossorigin=\"\"></script>");
        sb.append("<script>\n(function () {\n");
        sb.append("var map = L.map('map', {drawControl: true}).setView([30, 0], 2);\n");
        sb.append("L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {attribution: '&copy; OpenStreetMap'}).addTo(map);\n");
        sb.append("var dataByYear = {\n");
        sb.append("  \"").append(now).append("+").append(now + 1).append("\": [").append(markersJs(filterCurrent())).append("],\n");
        for (final var entry : byYear.entrySet()) {
            sb.append("  \"").append(entry.getKey()).append("\": [").append(markersJs(entry.getValue())).append("],\n");
        }
        sb.append("};\n");
        sb.append("var statsByYear = {\n");
        sb.append("  \"").append(now).append("+").append(now + 1).append("\": ").append(contextStatsJson(filterCurrent())).append(",\n");
        for (final var entry : byYear.entrySet()) {
            sb.append("  \"").append(entry.getKey()).append("\": ").append(contextStatsJson(entry.getValue())).append(",\n");
        }
        sb.append("};\n");
        sb.append("""
var group = L.layerGroup().addTo(map);
var select = document.getElementById('map-year');
var LABELS = { events: 'events', countries: 'countries', cfp: 'CFP open' };
function updateStats(key) {
  var stats = statsByYear[key];
  if (!stats) { return; }
  Object.keys(LABELS).forEach(function (k) {
    var el = document.getElementById('stat-' + k);
    if (el) { el.textContent = stats[k]; }
  });
}
function showYear(year) {
  group.clearLayers();
  var markers = dataByYear[year] || [];
  markers.forEach(function (m) { m.addTo(group); });
  var pts = markers.map(function (m) { return m.getLatLng(); });
  if (pts.length) { map.fitBounds(L.latLngBounds(pts).pad(0.25)); }
  updateStats(year);
}
if (select) {
  select.addEventListener('change', function () { showYear(select.value); });
  showYear(select.value);
}
})();
</script>""");

        return renderPage("Java Conferences Map", sb.toString(),
                Map.of("mapOnly", true, "showLeaflet", true,
                        "subtitle", "Pick a year (default: current + next) to see its conferences on the map. Click a marker for details and a link to the conference."));
    }

    private String markersJs(final List<Conference> confs) {
        final var sb = new StringBuilder();
        for (final var c : confs) {
            if (sb.length() > 0) {
                sb.append(",\n");
            }
            final var name = escape(c.name());
            final var detail = escape(c.date() + " &middot; " + c.locationName());
            final String link = c.link() == null || c.link().isBlank()
                    ? ""
                    : " &middot; <a href=\"" + escape(c.link()) + "\" target=\"_blank\" rel=\"noopener\">Link</a>";
            sb.append("      L.marker([").append(c.coordinates().lat()).append(",").append(c.coordinates().lon()).append("])")
                    .append(".bindPopup('<b>").append(name).append("</b><br/>").append(detail).append(link).append("')");
        }
        return sb.toString();
    }

    // --- JSON API + openapi ---------------------------------------------------

    private void writeApi(final Path target) throws IOException {
        final var apiDir = Files.createDirectories(target.resolve("api/conferences"));
        final var latest = filterCurrent();

        Files.writeString(target.resolve("conferences.json"), mapper.toString(latest));
        Files.writeString(apiDir.resolve("latest.json"), mapper.toString(latest));
        for (final var entry : byYear.entrySet()) {
            Files.writeString(apiDir.resolve(entry.getKey() + ".json"), mapper.toString(entry.getValue()));
        }

        // api/conferences/country/<CODE>.json : the conferences of that country (all years),
        // keyed by the uppercased ISO 3166-1 alpha-2 code from the geocoder coordinates.
        final var countryDir = Files.createDirectories(apiDir.resolve("country"));
        final var byCountry = new java.util.TreeMap<String, List<Conference>>();
        for (final var entry : byYear.entrySet()) {
            for (final var c : entry.getValue()) {
                final var code = countryCode(c);
                if (code == null) {
                    continue;
                }
                byCountry.computeIfAbsent(code, ignored -> new ArrayList<>()).add(c);
            }
        }
        for (final var e : byCountry.entrySet()) {
            Files.writeString(countryDir.resolve(e.getKey() + ".json"), mapper.toString(e.getValue()));
        }

        Files.writeString(target.resolve("api/openapi.js"), buildOpenApi());
        Files.writeString(target.resolve("api/index.html"), swaggerUiPage());
    }

    private String countryCode(final Conference c) {
        final var coord = c.coordinates();
        if (coord == null) {
            return null;
        }
        final String name = coord.countryName();
        if (name == null || name.isBlank() || "online".equalsIgnoreCase(name)) {
            return null;
        }
        var code = coord.countryCode();
        if ((code == null || code.isBlank()) && countries != null) {
            code = countries.find(name);
        }
        return code == null || code.isBlank() ? null : code.toUpperCase(java.util.Locale.ROOT);
    }

    private String buildOpenApi() {
        final var components = loadConferenceSchemas();
        return """
                window.swaggerSpec = {
                  openapi: "3.1.0",
                  info: { title: "Java Conferences API", version: "2026", description: "List of upcoming Java conferences." },
                  servers: [ { url: "https://javaconferences.org" } ],
                  components: { schemas: __SCHEMAS__ },
                  paths: {
                    "/api/conferences/latest.json": { get: { summary: "Conferences for the current and next year", operationId: "latest",
                      responses: { "200": { description: "OK", content: { "application/json": { schema: { type: "array", items: { $ref: "#/components/schemas/Conference" } } } } } } } },
                    "/api/conferences/{year}.json": { get: { summary: "Conferences for a given year", operationId: "yearly",
                      parameters: [ { name: "year", in: "path", required: true, schema: { type: "integer" } } ],
                      responses: { "200": { description: "OK", content: { "application/json": { schema: { type: "array", items: { $ref: "#/components/schemas/Conference" } } } } } } } },
                    "/api/conferences/country/{countryCode}.json": { get: { summary: "Conferences in a given country (uppercased ISO 3166-1 alpha-2 code)", operationId: "byCountry",
                      parameters: [ { name: "countryCode", in: "path", required: true, schema: { type: "string", description: "Uppercased ISO 3166-1 alpha-2 country code, e.g. FR, US, DE" } } ],
                      responses: { "200": { description: "OK", content: { "application/json": { schema: { type: "array", items: { $ref: "#/components/schemas/Conference" } } } } } } } }
                  }
                };
                """.replace("__SCHEMAS__", components);
    }

    /**
     * Wraps the rendered AsciiDoc of a year into a {@code section.year} card with a heading
     * (year + conference count) and an inner {@code .table-wrap} for horizontal scrolling on mobile.
     */
    private String yearSection(final int year, final String tableHtml) {
        final int count = byYear.getOrDefault(year, List.of()).size();
        return "<section class=\"year\" id=\"y" + year + "\">" +
                "<h2>" + year + " <span class=\"count\">" + count + " conferences</span></h2>" +
                "<div class=\"table-wrap\">" + tableHtml + "</div>" +
                "</section>";
    }

    /**
     * Loads the Conference/Coordinate/CfP schemas from the Fusion-generated schemas.json and
     * normalizes them for use in the OpenAPI document: the Fusion {@code $ref}s of the form
     * {@code #/schemas/<fqn>} are rewritten to {@code #/components/schemas/<shortName>} and the
     * referenced types ({@code Coordinate}, {@code CfP}) are emitted as sibling components so
     * swagger-ui can resolve them.
     */
    private String loadConferenceSchemas() {
        try (final var in = Files.newInputStream(schemaJson)) {
            final var root = mapper.read(Map.class, new java.io.StringReader(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));
            final var schemas = root.get("schemas");
            if (schemas instanceof Map<?, ?> map) {
                final var byShort = new java.util.LinkedHashMap<String, Object>();
                Object conference = null;
                for (final var e : map.entrySet()) {
                    final var key = String.valueOf(e.getKey());
                    final var shortName = shortName(key);
                    final var value = e.getValue();
                    if ("Conference".equals(key) || key.endsWith(".Conference")) {
                        conference = value;
                    } else if (key.endsWith(".CfP") || key.endsWith(".Coordinate")) {
                        byShort.putIfAbsent(shortName, value);
                    }
                }
                if (conference == null) {
                    return "{}";
                }
                final var components = new java.util.LinkedHashMap<String, Object>();
                components.put("Conference", jsonWithRewrittenRefs(conference, this::shortName));
                for (final var e : byShort.entrySet()) {
                    components.put(String.valueOf(e.getKey()), jsonWithRewrittenRefs(e.getValue(), this::shortName));
                }
                applySchemaDocumentation(components);
                return mapper.toString(components);
            }
        } catch (final Exception e) {
            logger.warning(() -> "Could not load Conference schema from " + schemaJson + ": " + e.getMessage());
        }
        return "{}";
    }

    /**
     * Enriches the OpenAPI component schemas with a human-readable component-level description and
     * normalizes a few Fusion codec artifacts. Per-property {@code description}s come straight from
     * the {@code @Property(documentation=...)} annotations on the source records (Fusion injects them
     * into {@code schemas.json}), and are carried through unchanged.
     */
    private void applySchemaDocumentation(final Map<String, Object> components) {
        addDescription(components, "Conference", COMPONENT_DOCS.get("Conference"));
        addDescription(components, "CfP", COMPONENT_DOCS.get("CfP"));
        addDescription(components, "Coordinate", COMPONENT_DOCS.get("Coordinate"));
        // Fusion renders a double as {"type":"integer","$id":"number"}; restore a clean numeric type.
        final var coord = components.get("Coordinate");
        if (coord instanceof Map<?, ?> coordMap) {
            final var props = coordMap.get("properties");
            if (props instanceof Map<?, ?> propsMap) {
                for (final var latLonKey : java.util.List.of("lat", "lon")) {
                    final var node = propsMap.get(latLonKey);
                    if (node instanceof Map<?, ?> nodeMap) {
                        final var copy = stringKeyedCopy(nodeMap);
                        copy.put("type", "number");
                        ((Map<String, Object>) propsMap).put(latLonKey, copy);
                    }
                }
            }
        }
    }

    private void addDescription(final Map<String, Object> components, final String name, final String doc) {
        if (doc == null || !(components.get(name) instanceof Map<?, ?> schema)) {
            return;
        }
        final var copy = stringKeyedCopy(schema);
        copy.put("description", doc);
        components.put(name, copy);
    }

    /** Copies a raw JSON map (possibly {@code Map<?,?}> with non-String keys) into a {@code Map<String, Object>}. */
    private Map<String, Object> stringKeyedCopy(final Map<?, ?> source) {
        final var copy = new java.util.LinkedHashMap<String, Object>();
        for (final var e : source.entrySet()) {
            copy.put(String.valueOf(e.getKey()), e.getValue());
        }
        return copy;
    }

    private String shortName(final String key) {
        final int dot = key.lastIndexOf('.');
        return dot < 0 ? key : key.substring(dot + 1);
    }

    /** Deep-copies a schema node, rewriting {@code #/schemas/<fqn>} refs to {@code #/components/schemas/<short>}. */
    private Object jsonWithRewrittenRefs(final Object node, final java.util.function.Function<String, String> shortNamer) {
        if (node instanceof Map<?, ?> map) {
            final var out = new java.util.LinkedHashMap<String, Object>();
            for (final var e : map.entrySet()) {
                final var key = String.valueOf(e.getKey());
                final var value = e.getValue();
                if (key.equals("$ref") && value instanceof String ref && ref.startsWith("#/schemas/")) {
                    out.put("$ref", "#/components/schemas/" + shortNamer.apply(ref.substring("#/schemas/".length())));
                } else if (key.equals("$id")) {
                    // drop the fusion-internal id, it is not meaningful in this document
                } else {
                    out.put(key, jsonWithRewrittenRefs(value, shortNamer));
                }
            }
            return out;
        } else if (node instanceof java.util.List<?> list) {
            return list.stream().map(it -> jsonWithRewrittenRefs(it, shortNamer)).toList();
        }
        return node;
    }

    private String swaggerUiPage() {
        return "<!DOCTYPE html>\n" +
                "<html lang=\"en\">\n" +
                "  <head>\n" +
                "    <meta charset=\"utf-8\">\n" +
                "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n" +
                "    <title>Java Conferences API docs</title>\n" +
                "    <link rel=\"stylesheet\" href=\"https://cdn.jsdelivr.net/npm/swagger-ui-dist@" + SWAGGER_UI_VERSION + "/swagger-ui.css\">\n" +
                "  </head>\n" +
                "  <body>\n" +
                "    <div id=\"swagger-ui\"></div>\n" +
                "    <script src=\"https://cdn.jsdelivr.net/npm/swagger-ui-dist@" + SWAGGER_UI_VERSION + "/swagger-ui-bundle.js\"></script>\n" +
                "    <script src=\"openapi.js\"></script>\n" +
                "    <script>\n" +
                "      SwaggerUIBundle({ spec: window.swaggerSpec, dom_id: '#swagger-ui' });\n" +
                "    </script>\n" +
                "  </body>\n" +
                "</html>\n";
    }

    // --- country column -------------------------------------------------------

    /** Injects a Country/flag column into a rendered AsciiDoc table for a given year. */
    private String injectCountryColumn(final int year, final String html) {
        final var confs = byYear.getOrDefault(year, List.of());
        final var out = new StringBuilder();
        int pos = 0;
        int confIdx = 0;
        while (true) {
            final int thead = html.indexOf("<thead>", pos);
            if (thead < 0) {
                out.append(html.substring(pos));
                break;
            }
            final int theadEnd = html.indexOf("</thead>", thead);
            if (theadEnd < 0) {
                throw new IllegalStateException("Missing </thead>");
            }
            final int headerRowEnd = html.indexOf("</tr>", thead);
            out.append(html, pos, headerRowEnd)
                    .append("<th>Country</th>")
                    .append(html, headerRowEnd, theadEnd + "</thead>".length());
            final int tbodyStart = html.indexOf("<tbody>", theadEnd);
            final int bodyStart = tbodyStart < 0 ? theadEnd + "</thead>".length() : tbodyStart + "<tbody>".length();
            int from = bodyStart;
            while (true) {
                final int endOfRow = html.indexOf("</tr>", from);
                if (endOfRow < 0) {
                    break;
                }
                final String sortKey = confIdx < confs.size() ? dateSortKey(confs.get(confIdx).date()) : null;
                out.append(html, from, endOfRow)
                        .append("<td>").append(confIdx < confs.size() ? countryCell(confs.get(confIdx)) : "").append("</td>")
                        .append("</tr>");
                if (sortKey != null) {
                    stampRowAttribute(out, endOfRow, sortKey);
                }
                from = endOfRow + "</tr>".length();
                confIdx++;
            }
            pos = from;
        }
        return out.toString();
    }

    /**
     * Adds {@code data-date="&lt;isoKey&gt;"} to the opening {@code <tr>} tag of the row that just
     * closed at {@code endOfRow}. The row opening tag is found by walking back from the last
     * {@code <tr>} emission point in the builder.
     */
    private void stampRowAttribute(final StringBuilder out, final int endOfRow, final String isoKey) {
        final int rowOpen = out.lastIndexOf("<tr>");
        if (rowOpen < 0) {
            return;
        }
        final var rowTag = new StringBuilder("<tr data-date=\"").append(isoKey).append("\">");
        out.replace(rowOpen, rowOpen + "<tr>".length(), rowTag.toString());
    }

    /**
     * Reduces a human-readable conference date to an ISO {@code yyyy-MM-dd} sort key: the first
     * day of the described range. Handles {@code 22-27 January 2026}, {@code 31 January-1 February 2026},
     * {@code 5th November 2024}, en-dashes ({@code 17–19 March 2026}) and month-first variants.
     * Returns null when no date can be extracted (the row then sorts last).
     */
    static String dateSortKey(final String date) {
        if (date == null || date.isBlank()) {
            return null;
        }
        final String lower = date.toLowerCase(java.util.Locale.ROOT).replace("\u2013", "-").replace("\u2014", "-");
        // day range within a single month: "22-27 January 2026" -> start day
        final var dayRange = java.util.regex.Pattern
                .compile("(\\d{1,2})\\s*-\\s*(\\d{1,2})(?:st|nd|rd|th)?\\s+([a-z]+)\\s+(\\d{4})")
                .matcher(lower);
        if (dayRange.find()) {
            final Integer month = monthIndex(dayRange.group(3));
            if (month != null) {
                return "%04d-%02d-%02d".formatted(Integer.parseInt(dayRange.group(4)), month, Integer.parseInt(dayRange.group(1)));
            }
            return null;
        }
        final var m = java.util.regex.Pattern
                .compile("(\\d{1,2})(?:st|nd|rd|th)?\\s+([a-z]+)(?:\\s|-)+(\\d{1,2})(?:st|nd|rd|th)?\\s+([a-z]+)\\s+(\\d{4})")
                .matcher(lower);
        if (m.find()) {
            final Integer startMonth = monthIndex(m.group(2));
            final Integer endMonth = monthIndex(m.group(4));
            if (startMonth != null && endMonth != null) {
                return "%04d-%02d-%02d".formatted(Integer.parseInt(m.group(5)), startMonth, Integer.parseInt(m.group(1)));
            }
            return null;
        }
        final var simple = java.util.regex.Pattern
                .compile("(\\d{1,2})(?:st|nd|rd|th)?\\s+([a-z]+)\\s+(\\d{4})")
                .matcher(lower);
        if (simple.find()) {
            final Integer month = monthIndex(simple.group(2));
            if (month != null) {
                return "%04d-%02d-%02d".formatted(Integer.parseInt(simple.group(3)), month, Integer.parseInt(simple.group(1)));
            }
        }
        return null;
    }

    private static Integer monthIndex(final String name) {
        final String[] months = {"january", "february", "march", "april", "may", "june",
                "july", "august", "september", "october", "november", "december"};
        for (int i = 0; i < months.length; i++) {
            if (months[i].equals(name)) {
                return i + 1;
            }
        }
        return null;
    }

    private String countryCell(final Conference c) {
        final var coords = c.coordinates();
        if (coords == null) {
            return "";
        }
        final var name = coords.countryName();
        if (name == null || name.isBlank()) {
            return "";
        }
        String iso = coords.countryCode();
        if (iso == null || iso.isBlank()) {
            iso = countries.find(name);
        }
        final var flag = "online".equalsIgnoreCase(name) ? "" :
                (iso == null ? "" : "<i class=\"fi fi-" + iso + "\"></i>&nbsp;");
        return flag + escape(name);
    }

    private List<Conference> filterCurrent() {
        final var now = Year.now().getValue();
        final var out = new ArrayList<Conference>();
        final var thisYear = byYear.get(now);
        final var nextYear = byYear.get(now + 1);
        if (thisYear != null) {
            out.addAll(thisYear);
        }
        if (nextYear != null) {
            out.addAll(nextYear);
        }
        return List.copyOf(out);
    }

    /** Minimal HTML escaping helper so injected text stays safe in the model. */
    private String escape(final String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}