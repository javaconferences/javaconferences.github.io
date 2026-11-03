package org.javaconference.generator;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;

/**
 * A geolocated conference city, resolved from the location name by the geocoder
 * or read directly from the source when coordinates are embedded in the text.
 */
@JsonModel
public record Coordinate(
        @Property(documentation = "Venue latitude.") double lat,
        @Property(documentation = "Venue longitude.") double lon,
        @Property(documentation = "Canonical country name.") String countryName,
        @Property(documentation = "ISO 3166-1 alpha-2 country code.") String countryCode) {
    public boolean isOnline() {
        return "Online".equalsIgnoreCase(countryName()) && lat() == 0 && lon() == 0;
    }
}