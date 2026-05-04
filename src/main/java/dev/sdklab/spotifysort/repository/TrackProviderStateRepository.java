package dev.sdklab.spotifysort.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import dev.sdklab.spotifysort.model.TrackProviderState;
import dev.sdklab.spotifysort.tagging.api.TagSource;

public interface TrackProviderStateRepository extends JpaRepository<TrackProviderState, Long> {

    Optional<TrackProviderState> findByTrackSpotifyIdAndSource(String trackSpotifyId, TagSource source);

    List<TrackProviderState> findByTrackSpotifyIdInAndSource(List<String> trackSpotifyIds, TagSource source);

    /** Returns all states for a given source whose refreshAfter is before the given cutoff (i.e. stale). */
    List<TrackProviderState> findBySourceAndRefreshAfterBefore(TagSource source, Instant cutoff);

    @Modifying
    @Query("DELETE FROM TrackProviderState s WHERE s.trackSpotifyId = :id AND s.source = :source")
    void deleteByTrackSpotifyIdAndSource(@Param("id") String trackSpotifyId, @Param("source") TagSource source);
}
