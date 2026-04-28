package dev.sdklab.spotifysort.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import dev.sdklab.spotifysort.model.User;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findBySpotifyId(String spotifyId);
}
