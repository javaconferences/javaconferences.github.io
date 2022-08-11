// how to run:
// $ jshell site.generator.java

import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.CompletableFuture.allOf;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;

record Coordinate(double lat, double lon) {
}

record Conference(String name, String link,
                  String locationName,
                  Coordinate coordinates,
                  boolean hybrid, String date) {
}

class ConferenceReader implements AutoCloseable {
    private final Logger logger = Logger.getLogger(getClass().getSimpleName());

    private final BufferedReader reader;
    private final String start;
    private final String end;
    private final HttpClient client;

    private Boolean skip;

    public ConferenceReader(final BufferedReader reader,
                            final String start, final String end) {
        this.reader = reader;
        this.start = start;
        this.end = end;
        this.client = HttpClient.newBuilder()
                .executor(ForkJoinPool.commonPool())
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();
    }

    public List<Conference> read() throws ExecutionException, InterruptedException {
        final var lines = reader.lines()
                .filter(it -> {
                    if (skip == null) {
                        if (it.startsWith(start)) {
                            skip = false;
                        }
                        return false;
                    }
                    if (it.startsWith(end)) {
                        skip = true;
                        return false;
                    }
                    return !skip && !it.isBlank();
                })
                .map(it -> Stream.of(it.strip().substring(1).split("\\|")).map(String::strip).toArray(String[]::new))
                .toList();

        final var locationRegistry = findLocations(lines);
        return lines.stream()
                .map(it -> parse(locationRegistry, it))
                .toList();
    }

    private Conference parse(final Map<String, Coordinate> locationRegistry, final String[] columns) {
        final boolean hasLinkInName = columns[0].startsWith("[");
        return new Conference(
                (hasLinkInName ? columns[0].substring(1, columns[0].indexOf(']')) : columns[0]).strip(),
                hasLinkInName ? columns[0].substring(columns[0].indexOf('(') + 1, columns[0].indexOf(')')) : "",
                columns[1], locationRegistry.get(columns[1]),
                "yes".equalsIgnoreCase(columns[2]) || "true".equalsIgnoreCase(columns[2]),
                columns[3]);
    }

    private Map<String, Coordinate> findLocations(final List<String[]> lines) throws InterruptedException, ExecutionException {
        final var locationLookups = lines.stream()
                .map(it -> it[1])
                .filter(it -> !it.isBlank())
                .distinct()
                .collect(toMap(identity(), this::findLocation));
        allOf(locationLookups.values().toArray(new CompletableFuture[0])).get();
        return locationLookups.entrySet().stream()
                .collect(toMap(Map.Entry::getKey, it -> it.getValue().getNow(null)));
    }

    private CompletableFuture<Coordinate> findLocation(final String name) {
        // first check it is not in the column already: "xxxx (lat, lon)", use dots, not commas for decimals.
        final int coordinateStart = name.indexOf('(');
        if (coordinateStart > 0) {
            final int coordinateEnd = name.indexOf(')', coordinateStart + 1);
            if (coordinateEnd < 0) {
                throw new IllegalArgumentException("Missing ')' in '" + name + "' (coordinates)");
            }
            final int sep = name.indexOf(',', coordinateStart);
            if (sep < 0) {
                throw new IllegalArgumentException("Missing ',' in '" + name + "' (coordinates)");
            }
            return completedFuture(new Coordinate(
                    Double.parseDouble(name.substring(1, sep).strip()),
                    Double.parseDouble(name.substring(sep + 1, coordinateEnd).strip())));
        }
        if ("online".equalsIgnoreCase(name)) { // TODO: refine
            return completedFuture(new Coordinate(0., 0.));
        }

        // try to lookup the location if not present
        // Note: "https://nominatim.openstreetmap.org" was down when writing the script
        final var uri = URI.create("https://nominatim.terrestris.de" +
                "/search.php?" +
                "q=" + URLEncoder.encode(name, UTF_8) + "&" +
                "polygon_geojson=1&" +
                "format=jsonv2&" +
                "limit=1");
        logger.info(() -> "Calling '" + uri + "'");
        return client.sendAsync(
                        HttpRequest.newBuilder()
                                .GET()
                                .uri(uri)
                                .timeout(Duration.ofMinutes(5))
                                .build(),
                        HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new IllegalArgumentException("Invalid response: " + response);
                    }
                    final var body = response.body();
                    if (body.contains("\"error\":{")) {
                        throw new IllegalArgumentException("Invalid response: " + body + " for '" + uri + "'");
                    }
                    return body;
                })
                .thenApply(json -> {
                    final int latStart = json.indexOf("\"lat\":\"");
                    if (latStart < 0) {
                        throw new IllegalArgumentException("Invalid lat start:" + latStart + "(" + json + ")");
                    }
                    final int latEnd = json.indexOf("\"", latStart + "\"lat\":\"".length() + 1);
                    if (latEnd < 0) {
                        throw new IllegalArgumentException("Invalid lat end:" + latEnd + "(" + json + ")");
                    }
                    final int lonStart = json.indexOf("\"lon\":\"");
                    if (lonStart < 0) {
                        throw new IllegalArgumentException("Invalid lon start:" + lonStart + "(" + json + ")");
                    }
                    final int lonEnd = json.indexOf("\"", lonStart + "\"lon\":\"".length() + 1);
                    if (lonEnd < 0) {
                        throw new IllegalArgumentException("Invalid lon end:" + lonEnd + "(" + json + ")");
                    }
                    return new Coordinate(
                            Double.parseDouble(json.substring(latStart + "\"lat\":\"".length(), latEnd)),
                            Double.parseDouble(json.substring(lonStart + "\"lon\":\"".length(), lonEnd)));
                });
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }
}

record GithubPages(Path source, Path output) {
    public void generate() throws Exception {
        final var logger = Logger.getLogger(getClass().getSimpleName());
        final var conferences = readConferences();
        logger.info(() -> "Found #" + conferences.size() + " conferences.");

        final var target = Files.createDirectories(output);

        final var extensions = List.of(TablesExtension.create());
        final var parser = Parser.builder().extensions(extensions).build();
        final var renderer = HtmlRenderer.builder().extensions(extensions).build();

        Files.writeString(
                target.resolve("index.html"),
                """
                        <!DOCTYPE html>
                        <html>
                          <head>
                            <meta charset="utf-8">
                            <meta name="viewport" content="width=device-width initial-scale=1" />
                            <meta http-equiv="X-UA-Compatible" content="IE=edge">
                            <title>Java Conferences</title>
                            <link href="https://pages-themes.github.io/minimal/assets/css/style.css?v=814b8723af0aa0ada9b5784da6b73d862bb74150" rel="stylesheet" />
                        </head>
                        <body>""" +
                        renderer.render(parser.parse(Files.readString(source)))
                                .replace("</h1>", "</h1>\n<p>See it as a <a href=\"/map.html\">map</a></p>\n") +
                        "</body></html>");
        Files.writeString(target.resolve("map.html"), "" +
                "<!DOCTYPE html>\n" +
                "<html lang=\"en\">\n" +
                "  <head>\n" +
                "    <meta charset=\"utf-8\" />\n" +
                "    <meta http-equiv=\"x-ua-compatible\" content=\"ie=edge\" />\n" +
                "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" />\n" +
                "\n" +
                "    <title>Java Conferences</title>\n" +
                "    <link rel=\"stylesheet\" href=\"https://unpkg.com/leaflet@1.8.0/dist/leaflet.css\"\n" +
                "      integrity=\"sha512-hoalWLoI8r4UszCkZ5kL8vayOGVae1oxXe/2A4AO6J9+580uKHDO3JdHb7NzwwzK5xr/Fs0W40kiNHxM9vyTtQ==\"\n" +
                "      crossorigin=\"\"/>\n" +
                "    <style>\n" +
                "      body { margin: 0; padding: 0; }\n" +
                "      #map { height: 100vh; }\n" +
                "    </style>\n" +
                "  </head>\n" +
                "\n" +
                "  <body>\n" +
                "    <div id=\"map\"></div>" +
                "    <script src=\"https://unpkg.com/leaflet@1.8.0/dist/leaflet.js\"\n" +
                "      integrity=\"sha512-BB3hKbKWOc9Ez/TAwyWxNXeoV9c1v6FIeYiBieIWkpLjauysF18NzgR1MBNBXf8/KABdlkX68nAhlwcDFLGPCQ==\"\n" +
                "      crossorigin=\"\"></script>\n" +
                "    <script>\n" +
                "      (function () {\n" +
                "          var map = L.map('map', {drawControl: true}).setView([51.505, -0.09], 13);\n" +
                "          L.tileLayer('http://{s}.tile.osm.org/{z}/{x}/{y}.png', {\n" +
                "              attribution: '&copy; <a href=\"http://osm.org/copyright\">OpenStreetMap</a>'\n" +
                "          }).addTo(map);\n" +
                conferences.stream()
                        .map(c -> {
                            final var title = c.name() + "<br>" + c.date() + "<br>" + c.locationName() +
                                    (c.link().isBlank() ? "" : ("<br><a href=\"" + c.link() + "\">Link</a>"));
                            return "" +
                                    "            L.marker([" +
                                    c.coordinates().lat() + "," + c.coordinates().lon() + "], {" +
                                    "alt:'" + c.locationName() + "', " +
                                    "title:'" + title +
                                    "'}).bindTooltip('" + title + "')";
                        })
                        .collect(joining(",\n", "          var markers = [\n", "\n          ];\n")) +
                "          var group = L.featureGroup(markers).addTo(map);\n" +
                "          map.fitBounds(group.getBounds());\n" +
                "          L.Control.textbox = L.Control.extend({\n" +
                "            onAdd: function(map) {\n" +
                "              var title = L.DomUtil.create('div');\n" +
                "              title.id = 'map-title';\n" +
                "              title.innerHTML = '<h1>Java Conferences Map</h1>'\n" +
                "              return title;\n" +
                "            },\n" +
                "            onRemove: function(map) {}\n" +
                "          });\n" +
                "          L.control.textbox = function(opts) { return new L.Control.textbox(opts); }\n" +
                "          L.control.textbox({position: 'topleft'}).addTo(map);" +
                "      })();\n" +
                "    </script>\n" +
                "  </body>\n" +
                "</html>" +
                "");
        logger.info(() -> "Generation successful.");
    }

    private List<Conference> readConferences() throws Exception {
        if (!Files.exists(source)) {
            throw new IllegalArgumentException("Invalid source: '" + source + "'");
        }
        try (final var reader = new ConferenceReader(Files.newBufferedReader(source), "| ---", "## ")) {
            return reader.read();
        }
    }
}

class Runner { // enables to code in an IDE even if not needed by JShell
    public static void main(final String... args) throws Exception {
        new GithubPages(Path.of("README.md"), Path.of("target/pages")).generate();
    }
}

Runner.main(new String[0]);
/exit
