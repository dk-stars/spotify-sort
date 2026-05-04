package dev.sdklab.spotifysort.model;

import java.time.Instant;

import dev.sdklab.spotifysort.tagging.api.ProviderLookupStatus;
import dev.sdklab.spotifysort.tagging.api.TagSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Records the outcome of the most recent provider lookup for a specific track+provider pair.
 *
 * <p>Negative caching: when status is UNKNOWN or AMBIGUOUS, the {@code refreshAfter} field
 * keeps the LLM from being called again for the same track until the TTL expires.
 * This prevents repeated billing for tracks the model cannot identify.
 *
 * <p>One row per (trackSpotifyId, source). Upserted on every provider call.
 */
@Entity
@Table(
    name = "track_provider_state",
    indexes = {
        @Index(name = "idx_tps_track_source", columnList = "trackSpotifyId, source"),
        @Index(name = "idx_tps_refresh_after", columnList = "source, refreshAfter")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_tps_track_source", columnNames = {"trackSpotifyId", "source"})
    }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TrackProviderState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String trackSpotifyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TagSource source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ProviderLookupStatus status;

    /** When this record was last written. */
    @Column(nullable = false)
    private Instant fetchedAt;

    /** Do not call the provider again until this time has passed. */
    @Column(nullable = false)
    private Instant refreshAfter;

    /** Model name used for LLM calls (null for Last.fm). */
    @Column(length = 128)
    private String modelName;

    /** Match strategy returned by the LLM (ISRC, ARTIST_TRACK_ALBUM, etc.). */
    @Column(length = 64)
    private String matchStrategy;

    /** Reason code returned by the LLM (KNOWN_MATCH, NOT_ENOUGH_EVIDENCE, etc.). */
    @Column(length = 64)
    private String reasonCode;

    /** Confidence score returned by the LLM (0.0–1.0). Null for Last.fm. */
    private Double confidence;
}
