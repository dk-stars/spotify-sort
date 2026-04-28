package dev.sdklab.spotifysort.controller;

import dev.sdklab.spotifysort.model.User;
import dev.sdklab.spotifysort.repository.UserRepository;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import se.michaelthelin.spotify.SpotifyApi;
import se.michaelthelin.spotify.model_objects.credentials.AuthorizationCodeCredentials;
import se.michaelthelin.spotify.requests.authorization.authorization_code.AuthorizationCodeUriRequest;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class SpotifyAuthController {

    private final SpotifyApi spotifyApi;
    private final UserRepository userRepository;

    @Value("${spotify.frontend-url}")
    private String frontendUrl;

    @GetMapping("/login")
    public ResponseEntity<Void> login() {
        AuthorizationCodeUriRequest request = spotifyApi.authorizationCodeUri()
                .scope("playlist-read-private,playlist-modify-private,playlist-modify-public,user-read-private")
                .build();

        URI uri = request.execute();
        return ResponseEntity.status(HttpStatus.FOUND).location(uri).build();
    }

    @GetMapping("/callback")
    public ResponseEntity<Void> callback(@RequestParam("code") String code, HttpSession session) {
        try {
            AuthorizationCodeCredentials credentials =
                    spotifyApi.authorizationCode(code).build().execute();

            // Fetch Spotify profile using a temporary per-request SpotifyApi instance
            SpotifyApi tempApi = new SpotifyApi.Builder()
                    .setAccessToken(credentials.getAccessToken())
                    .build();
            se.michaelthelin.spotify.model_objects.specification.User spotifyUser =
                    tempApi.getCurrentUsersProfile().build().execute();

            Instant expiresAt = Instant.now().plusSeconds(credentials.getExpiresIn());

            // Upsert user in DB
            User user = userRepository.findBySpotifyId(spotifyUser.getId())
                    .orElse(User.builder()
                            .spotifyId(spotifyUser.getId())
                            .build());

            user.setAccessToken(credentials.getAccessToken());
            user.setRefreshToken(credentials.getRefreshToken());
            user.setTokenExpiresAt(expiresAt);
            if (spotifyUser.getDisplayName() != null) {
                user.setDisplayName(spotifyUser.getDisplayName());
            }

            User savedUser = userRepository.save(user);
            session.setAttribute("userId", savedUser.getId());

            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(frontendUrl + "/"))
                    .build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(frontendUrl + "/?error=auth_failed"))
                    .build();
        }
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(
            @SessionAttribute(name = "userId", required = false) Long userId) {
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return userRepository.findById(userId)
                .map(u -> ResponseEntity.ok(Map.of(
                        "userId", u.getId(),
                        "spotifyId", u.getSpotifyId(),
                        "displayName", u.getDisplayName() != null ? u.getDisplayName() : ""
                )))
                .orElse(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpSession session) {
        session.invalidate();
        return ResponseEntity.noContent().build();
    }
}
