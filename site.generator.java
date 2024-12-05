// how to run:
// $ jshell site.generator.java

import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import com.fasterxml.jackson.databind.ObjectMapper;

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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Locale.ROOT;
import static java.util.Optional.ofNullable;
import static java.util.concurrent.CompletableFuture.allOf;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;

record Coordinate(double lat, double lon, String countryName) {
}

record Conference(String name, String link,
                  String locationName,
                  Coordinate coordinates,
                  boolean hybrid, String date, String cfpLink, String cfpEndDate) {
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
                    if (it.startsWith("#")) {
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
        final boolean hasCfpLink = columns[4].startsWith("[");

        String cfpLink ="";
        String cfpClose = "";

        if (hasCfpLink) {
            cfpLink = columns[4].substring(columns[4].indexOf('(') + 1, columns[4].indexOf(')'));
            int closeIndex = columns[4].indexOf("Closes");
            if (closeIndex == -1) {
                closeIndex = columns[4].indexOf("Closed");
            }
            if (closeIndex != -1) {
                cfpClose = columns[4].substring(closeIndex + 7, columns[4].indexOf(')', closeIndex));
            }
        }

        return new Conference(
                (hasLinkInName ? columns[0].substring(1, columns[0].indexOf(']')) : columns[0]).strip(),
                hasLinkInName ? columns[0].substring(columns[0].lastIndexOf('(') + 1, columns[0].lastIndexOf(')')) : "",
                columns[1], locationRegistry.get(columns[1]),
                "yes".equalsIgnoreCase(columns[2]) || "true".equalsIgnoreCase(columns[2]),
                columns[3],
                cfpLink,
                cfpClose
                );
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
        // first check it is not in the column already: "city, country (lat, lon)", use dots, not commas for decimals.
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
            final var citySep = name.indexOf(',');
            if (citySep < 0) {
                throw new IllegalArgumentException("Missing city in '" + name + "'");
            }
            final var endCity = name.lastIndexOf(' ', coordinateStart);
            if (endCity < 0) {
                throw new IllegalArgumentException("Missing city in '" + name + "'");
            }
            return completedFuture(new Coordinate(
                    Double.parseDouble(name.substring(1, sep).strip()),
                    Double.parseDouble(name.substring(sep + 1, coordinateEnd).strip()),
                    name.substring(citySep, endCity).strip()));
        }
        if ("online".equalsIgnoreCase(name)) { // TODO: refine
            return completedFuture(new Coordinate(0., 0., "Online"));
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
                                .header("accept-language", "en-EN,en")
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
                    final int startName = json.indexOf("\"display_name\":\"");
                    if (startName < 0) {
                        throw new IllegalArgumentException("No display name for '" + name + "'");
                    }
                    final int endName = json.indexOf("\"", startName + "\"display_name\":\"".length() + 1);
                    if (endName < 0) {
                        throw new IllegalArgumentException("No display name for '" + name + "'");
                    }
                    final var displayNameSegments = json.substring(startName + "\"display_name\":\"".length(), endName).split(",");
                    return new Coordinate(
                            Double.parseDouble(json.substring(latStart + "\"lat\":\"".length(), latEnd)),
                            Double.parseDouble(json.substring(lonStart + "\"lon\":\"".length(), lonEnd)),
                            displayNameSegments[displayNameSegments.length - 1].strip());
                });
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }
}

class Countries {
    private final Properties countryIsoCodes = new Properties();

    public Countries() {
        try (final var reader = Files.newBufferedReader(Path.of("countries.properties"))) {
            countryIsoCodes.load(reader);
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public Optional<String> find(final String name) {
        return ofNullable(countryIsoCodes.getProperty(name.toLowerCase(ROOT)
                .replace(" ", "")
                .replace(",", "")
                .replace("'", "")
                .replace("ç", "c")
                .replace("ô", "o")
                .replace("é", "e")
                .replace("å", "a")));
    }
}

record GithubPages(Path source, Path output) {
    public void generate() throws Exception {
        final var extensions = List.of(TablesExtension.create());
        final var parser = Parser.builder().extensions(extensions).build();
        final var renderer = HtmlRenderer.builder().extensions(extensions).build();
        final var countries = new Countries();
        final var logger = Logger.getLogger(getClass().getSimpleName());

        final var conferences = readConferences();
        logger.info(() -> "Found #" + conferences.size() + " conferences.");

        final var target = Files.createDirectories(output);

        Files.writeString(target.resolve(".nojekll"), "");
        Files.writeString(target.resolve("index.html"), index(parser, renderer, conferences, countries));
        Files.writeString(target.resolve("map.html"), map(conferences));
        Files.writeString(target.resolve("conferences.json"), mapToJson(conferences));
        logger.info(() -> "Generation successful.");
    }

    private String mapToJson(final List<Conference> conferences) throws Exception {
        return new ObjectMapper().writeValueAsString(conferences);
    }

    private String map(final List<Conference> conferences) {
        return "" +
                "<!DOCTYPE html>\n" +
                "<html lang=\"en\">\n" +
                "  <head>\n" +
                "    <meta charset=\"utf-8\" />\n" +
                "    <meta http-equiv=\"x-ua-compatible\" content=\"ie=edge\" />\n" +
                "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" />\n" +
                "\n" +
                "    <title>Java Conferences</title>\n" +
                leafletCss() +
                "    <style>\n" +
                "      body { margin: 0; padding: 0; }\n" +
                "      #map { height: 100vh; }\n" +
                "    </style>\n" +
                "  </head>\n" +
                "\n" +
                "  <body>\n" +
                mapContent(conferences) +
                "  </body>\n" +
                "</html>" +
                "";
    }

    private String mapContent(final List<Conference> conferences) {
        return "" +
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
                                    (c.link().isBlank() ? "" : ("<br><a" +
                                            " href=\"" + c.link() + "\"" +
                                            " onmousedown=\"" +
                                            "if (window.unbindTooltip) { window.unbindTooltip.unbind(); window.unbindTooltip = undefined; }" +
                                            "setTimeout(function () {window.open(\\'" + c.link() + "\\', \\'_blank\\').focus();}, 100)\"" +
                                            ">Link</a>"));
                            return "            " +
                                    "{ " +
                                    "tooltip: '" + title + "', " +
                                    "marker: L.marker([" +
                                    c.coordinates().lat() + "," + c.coordinates().lon() + "], {" +
                                    "alt:'" + c.locationName() + "', " +
                                    "title:'" + title +
                                    "'}) " +
                                    "}";
                        })
                        .collect(joining(",\n", "          var markers = [\n", "\n          ];\n")) +
                "          markers.forEach(function (marker) {\n" +
                "            marker.marker.on('mouseover', function() {\n" + // workaround to make it a bit persistent and enable to click on links
                "              marker.marker.bindTooltip(marker.tooltip, {permanent: true, interactive:true});\n" +
                "              if (window.unbindTooltip && window.unbindTooltip.marker != marker) { window.unbindTooltip.unbind(); }\n" +
                "              unbindTooltip = {" +
                "                version: window.unbindTooltip && window.unbindTooltip.marker == marker ? window.unbindTooltip.version + 1 : 1," +
                "                marker: marker, " +
                "                unbind: function () { marker.marker.unbindTooltip(); window.unbindTooltip = undefined; }" +
                "              };\n" +
                "            });\n" +
                "            marker.marker.on('mouseout', function() {\n" +
                "              var version = window.unbindTooltip ? window.unbindTooltip.version : -1;\n" +
                "              window.unbindTooltip && setTimeout(function () {\n" +
                "                window.unbindTooltip && version == window.unbindTooltip.version && window.unbindTooltip.unbind();\n" +
                "                window.unbindTooltip = undefined;\n" +
                "              }, 3000);\n" +
                "            });\n" +
                "          });\n" +
                "          var group = L.featureGroup(markers.map(function (it) { return it.marker; })).addTo(map);\n" +
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
                "    </script>\n";
    }

    private String index(final Parser parser, final HtmlRenderer renderer,
                         final List<Conference> conferences, final Countries countries) throws IOException {
        final var html = renderer.render(parser.parse(Files.readString(source)));
        final var patchedBody =
                injectTableFilter(
                        injectMap(
                                injectConferenceLocation(html, conferences, countries),
                                conferences));
        return """
                <!DOCTYPE html>
                <html>
                  <head>
                    <meta charset="utf-8">
                    <meta name="viewport" content="width=device-width initial-scale=1" />
                    <meta http-equiv="X-UA-Compatible" content="IE=edge">
                    <title>Java Conferences</title>
                    <link href="https://pages-themes.github.io/minimal/assets/css/style.css?v=814b8723af0aa0ada9b5784da6b73d862bb74150" rel="stylesheet" />
                    <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/flag-icon-css/6.6.4/css/flag-icons.min.css" integrity="sha512-uvXdJud8WaOlQFjlz9B15Yy2Au/bMAvz79F7Xa6OakCl2jvQPdHD0hb3dEqZRdSwG4/sknePXlE7GiarwA/9Wg==" crossorigin="anonymous" referrerpolicy="no-referrer" />""" +
                leafletCss() + """
                    <style>
                      input[data-filter] {
                        width: 100%;
                        margin: 4px 0;
                        display: inline-block;
                        border: 1px solid #ccc;
                        box-shadow: inset 0 1px 3px #ddd;
                        border-radius: 4px;
                        box-sizing: border-box;
                        padding-left: 10px;
                        padding-right: 10px;
                        padding-top: 6px;
                        padding-bottom: 6px;
                      }
                      #map {
                        height: 400px;
                        margin: 1rem 0 1rem 0;
                      }
                    </style>
                </head>
                <body>""" +
                patchedBody +
                "  <script>\n" +
                "    window.addEventListener('DOMContentLoaded', function () {\n" +
                "      var conferenceFilter = document.querySelector('input[data-filter=\"conferences\"]');\n" +
                "      var conferenceTable = document.getElementById('conferences');\n" +
                "      var conferenceTableTrs = Array.from(conferenceTable.querySelectorAll('tbody > tr'))\n" +
                "            .map(function (e) {\n" +
                "              return {text: (e.innerText || e.textContent).toLowerCase(), element: e, display: e.style.display};\n" +
                "            });\n" +
                "      conferenceFilter.addEventListener('keyup', function (e) {\n" + // todo: debounce? not critical yet
                "        var filter = (conferenceFilter.value || '').toLowerCase().split(' ');\n" + // todo: support AND/OR keywords?
                "        conferenceTableTrs.forEach(function (data) {\n" +
                "          data.element.style.display = filter.some(function (it) { return data.text.indexOf(it) >= 0; }) ?\n" +
                "                                 data.display : 'none';\n" +
                "        });\n" +
                "      });\n" +
                "    });\n" +
                "  </script>\n" +
                "</body>\n" +
                "</html>\n";
    }

    private String injectTableFilter(final String html) {
        return html.replaceFirst("<table>", "" +
                "<input data-filter=\"conferences\" type=\"text\" placeholder=\"Filter...\">\n" +
                "<table id=\"conferences\">");
    }

    private String injectMap(final String html, final List<Conference> conferences) {
        return html.replace(
                "</table>",
                "</table>\n" +
                        "<p>" +
                        "View" +
                        " <a" +
                        " target=\"_blank\"" +
                        " href=\"map.html\"" +
                        " >map</a>" +
                        " only." +
                        "</p>\n" + mapContent(conferences));
    }

    private String injectConferenceLocation(final String html, final List<Conference> conferences, final Countries countries) {
        int from = html.indexOf("</tr>"); // first is the end of title so inject the new column
        if (from < 0) {
            throw new IllegalArgumentException("Missing table of conferences");
        }

        var out = new StringBuilder(html.substring(0, from))
                .append("<th>Country</th>");
        for (final var conference : conferences) {
            final int endOfRow = html.indexOf("</tr>", from + "</tr>".length());
            if (endOfRow < 0) {
                throw new IllegalArgumentException("Missing table of conferences");
            }

            out.append(html, from, endOfRow)
                    .append("<td>")
                    .append(countries
                            .find(conference.coordinates().countryName())
                            .map(c -> "<i class=\"fi fis fi-" + c + "\"></i>&nbsp;")
                            .orElse(""))
                    .append(conference.coordinates().countryName())
                    .append("</td>");
            from = endOfRow;
        }
        out.append(html, from, html.length());
        return out.toString();
    }

    private String leafletCss() {
        return "" +
                "    <link rel=\"stylesheet\" href=\"https://unpkg.com/leaflet@1.8.0/dist/leaflet.css\"\n" +
                "      integrity=\"sha512-hoalWLoI8r4UszCkZ5kL8vayOGVae1oxXe/2A4AO6J9+580uKHDO3JdHb7NzwwzK5xr/Fs0W40kiNHxM9vyTtQ==\"\n" +
                "      crossorigin=\"anonymous\" referrerpolicy=\"no-referrer\"/>\n";
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
