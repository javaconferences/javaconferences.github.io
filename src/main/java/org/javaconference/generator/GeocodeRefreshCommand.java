package org.javaconference.generator;

import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;

import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * CLI command that re-resolves every distinct location in {@code README.adoc} through Nominatim and
 * writes the merged result back to the committed growing cache ({@code locations-cache.properties}).
 *
 * <p>Locations already covered by the curated seed or the existing cache resolve offline; only unknown or
 * changed ones hit the live geocoder. It is meant to run on a schedule (a weekly GitHub Action) so the
 * committed cache stays fresh without the full site-generation side effects.
 * Equivalent to {@code mvn clean process-classes -Dcommand=refresh-locations}.
 */
@DefaultScoped
@Command(name = "refresh-locations", description = "Re-resolve all locations and refresh the committed geocoding cache.")
public class GeocodeRefreshCommand implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(GeocodeRefreshCommand.class.getName());

    private final Config config;
    private final ConferenceReader reader;

    public GeocodeRefreshCommand(final Config config, final ConferenceReader reader) {
        this.config = config;
        this.reader = reader;
    }

    @Override
    public void run() {
        try {
            reader.read(Path.of(config.source()));
            LOGGER.info(() -> "Locations refreshed into " + ConferenceReader.DEFAULT_PERSISTED_CACHE + ".");
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @RootConfiguration("refresh-locations")
    public record Config(
            @Property(value = "source", defaultValue = "\"README.adoc\"", documentation = "AsciiDoc source file holding the conferences.")
            String source) {
    }
}