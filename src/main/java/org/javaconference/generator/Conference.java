package org.javaconference.generator;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;

/**
 * A single conference.
 *
 * <p>The JSON serialization of this record is what the public API exposes
 * (see {@code api/conferences/*.json}). The {@code @JsonModel} annotation lets the
 * FusionProcessor generate the matching JSON-Schema under
 * {@code META-INF/fusion/json/schemas.json}.
 */
@JsonModel
public record Conference(
        @Property(documentation = "Conference name.") String name,
        @Property(documentation = "Official conference website.") String link,
        @Property(documentation = "Human-readable venue city and country.") String locationName,
        @Property(documentation = "Geolocated venue coordinates.") Coordinate coordinates,
        @Property(documentation = "Whether the conference offers a live/remote hybrid format.") boolean hybrid,
        @Property(documentation = "Human-readable edition dates.") String date,
        @Property(documentation = "Call-for-papers details, if any.") CfP cfp) {
    /** Call-for-papers details, nested as a single object with link + closing date. */
    @JsonModel
    public record CfP(@Property(documentation = "Call-for-papers submission page.") String link,
                      @Property(documentation = "Closing date of the call for papers (YYYY-MM-DD, empty if unknown).") String endDate) {
    }
}