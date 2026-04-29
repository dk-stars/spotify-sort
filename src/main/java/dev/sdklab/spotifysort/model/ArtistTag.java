package dev.sdklab.spotifysort.model;

import java.time.Instant;

import dev.sdklab.spotifysort.tagging.api.TagSource;
import dev.sdklab.spotifysort.tagging.api.TagType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "artist_tags", indexes = {
        @Index(name = "idx_artist_tags_spotify_source", columnList = "artistSpotifyId, source")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ArtistTag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String artistSpotifyId;

    @Column(name = "tag_value", nullable = false)
    private String value;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TagType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TagSource source;

    @Column(nullable = false)
    private int weight;

    @Column(nullable = false)
    private Instant cachedAt;
}
