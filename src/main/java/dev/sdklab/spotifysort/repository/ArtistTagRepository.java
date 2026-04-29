package dev.sdklab.spotifysort.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import dev.sdklab.spotifysort.model.ArtistTag;
import dev.sdklab.spotifysort.tagging.api.TagSource;

public interface ArtistTagRepository extends JpaRepository<ArtistTag, Long> {
    List<ArtistTag> findByArtistSpotifyId(String artistSpotifyId);
    void deleteByArtistSpotifyIdAndSource(String artistSpotifyId, TagSource source);
    boolean existsByArtistSpotifyIdAndSource(String artistSpotifyId, TagSource source);
}
