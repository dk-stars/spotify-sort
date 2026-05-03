package dev.sdklab.spotifysort.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import dev.sdklab.spotifysort.model.TrackTag;
import dev.sdklab.spotifysort.tagging.api.TagSource;

public interface TrackTagRepository extends JpaRepository<TrackTag, Long> {
    List<TrackTag> findByTrackSpotifyId(String trackSpotifyId);
    List<TrackTag> findByTrackSpotifyIdAndSource(String trackSpotifyId, TagSource source);
    List<TrackTag> findByTrackSpotifyIdInAndSource(List<String> trackSpotifyIds, TagSource source);
    void deleteByTrackSpotifyIdAndSource(String trackSpotifyId, TagSource source);
    boolean existsByTrackSpotifyIdAndSource(String trackSpotifyId, TagSource source);
}
