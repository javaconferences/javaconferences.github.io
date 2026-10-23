package org.javaconference.generator;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import static java.util.Locale.ROOT;
import static java.util.Optional.ofNullable;

/**
 * Maps a country name (as returned by the geocoder) to its ISO 3166-1 alpha-2 code using the
 * {@code countries.properties} resource bundled on the classpath ({@code src/main/resources}).
 */
public class Countries {
    private final Properties countryIsoCodes = new Properties();

    public Countries() {
        try (final InputStream in = Countries.class.getResourceAsStream("/countries.properties")) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource /countries.properties");
            }
            countryIsoCodes.load(in);
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public String find(final String name) {
        return ofNullable(countryIsoCodes.getProperty(normalize(name))).orElse(null);
    }

    private String normalize(final String name) {
        return name.toLowerCase(ROOT)
                .replace(" ", "")
                .replace(",", "")
                .replace("'", "")
                .replace("ç", "c")
                .replace("ô", "o")
                .replace("é", "e")
                .replace("å", "a");
    }
}