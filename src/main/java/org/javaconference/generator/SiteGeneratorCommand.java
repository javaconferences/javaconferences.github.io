package org.javaconference.generator;

import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;

import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * CLI command that generates the static site + JSON API (equivalent to
 * {@code mvn clean process-classes -Dcommand=site}).
 */
@DefaultScoped
@Command(name = "site", description = "Generate the static site + JSON API.")
public class SiteGeneratorCommand implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(SiteGeneratorCommand.class.getName());

    private final Config config;
    private final SiteGenerator generator;

    public SiteGeneratorCommand(final Config config, final SiteGenerator generator) {
        this.config = config;
        this.generator = generator;
    }

    @Override
    public void run() {
        try {
            generator.generate(Path.of(config.source()), Path.of(config.output()));
            LOGGER.info(() -> "Site generated into target/pages.");
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @RootConfiguration("site")
    public record Config(
            @Property(value = "source", defaultValue = "\"README.adoc\"", documentation = "AsciiDoc source file holding the conferences.")
            String source,
            @Property(value = "output", defaultValue = "\"target/pages\"", documentation = "Directory where the static site and JSON API are written.")
            String output) {
    }
}