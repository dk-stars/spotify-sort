package dev.sdklab.spotifysort.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import dev.sdklab.spotifysort.model.Artist;

public interface ArtistRepository extends JpaRepository<Artist, Long> {
    Optional<Artist> findBySpotifyId(String spotifyId);
    boolean existsBySpotifyId(String spotifyId);
}
