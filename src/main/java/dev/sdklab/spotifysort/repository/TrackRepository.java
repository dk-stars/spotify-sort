package dev.sdklab.spotifysort.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import dev.sdklab.spotifysort.model.Track;

public interface TrackRepository extends JpaRepository<Track, Long> {
    Optional<Track> findBySpotifyId(String spotifyId);
    boolean existsBySpotifyId(String spotifyId);
    List<Track> findAllBySpotifyIdIn(Collection<String> spotifyIds);
}
