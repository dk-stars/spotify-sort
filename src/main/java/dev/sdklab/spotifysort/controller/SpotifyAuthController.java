package dev.sdklab.spotifysort.controller;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestAttribute;

import dev.sdklab.spotifysort.model.User;
import dev.sdklab.spotifysort.repository.UserRepository;
import dev.sdklab.spotifysort.service.JwtUtil;
import dev.sdklab.spotifysort.service.TokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import se.michaelthelin.spotify.SpotifyApi;
import se.michaelthelin.spotify.SpotifyHttpManager;
import se.michaelthelin.spotify.enums.AuthorizationScope;
import se.michaelthelin.spotify.model_objects.credentials.AuthorizationCodeCredentials;
import se.michaelthelin.spotify.requests.authorization.authorization_code.AuthorizationCodeUriRequest;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
public class SpotifyAuthController {

    private final SpotifyApi spotifyApi;
    private final UserRepository userRepository;
    private final TokenService tokenService;
    private final JwtUtil jwtUtil;

    @Value("${spotify.frontend-url}")
    private String frontendUrl;

    @Value("${spotify.redirect-uri}")
    private String redirectUri;

    @GetMapping("/login")
    public ResponseEntity<Void> login() {
        AuthorizationCodeUriRequest request = spotifyApi.authorizationCodeUri()
                .scope(
                        AuthorizationScope.PLAYLIST_READ_PRIVATE,
                        AuthorizationScope.PLAYLIST_READ_COLLABORATIVE,
                        AuthorizationScope.PLAYLIST_MODIFY_PRIVATE,
                        AuthorizationScope.PLAYLIST_MODIFY_PUBLIC,
                        AuthorizationScope.USER_READ_PRIVATE,
                        AuthorizationScope.USER_LIBRARY_READ
                )
                .show_dialog(true)
                .build();

        URI uri = request.execute();
        return ResponseEntity.status(HttpStatus.FOUND).location(uri).build();
    }

    @GetMapping("/callback")
    public ResponseEntity<Void> callback(@RequestParam("code") String code) {
        try {
            // Create a fresh SpotifyApi instance for code exchange (singleton may have stale state)
            SpotifyApi authApi = new SpotifyApi.Builder()
                    .setClientId(spotifyApi.getClientId())
                    .setClientSecret(spotifyApi.getClientSecret())
                    .setRedirectUri(SpotifyHttpManager.makeUri(redirectUri))
                    .build();

            AuthorizationCodeCredentials credentials =
                    authApi.authorizationCode(code).build().execute();

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
                        if (spotifyUser.getImages() != null && spotifyUser.getImages().length > 0) {
                                user.setAvatarUrl(spotifyUser.getImages()[0].getUrl());
                        }

            User savedUser = userRepository.save(user);
            String token = jwtUtil.issue(savedUser.getId());

            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(frontendUrl + "/?token=" + token))
                    .build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(frontendUrl + "/?error=auth_failed"))
                    .build();
        }
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(
            @RequestAttribute(name = "userId", required = false) Long userId) {
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return userRepository.findById(userId)
                .map(u -> {
                                        User resolvedUser = syncUserProfile(u);
                    return ResponseEntity.ok(Map.of(
                                                "userId", resolvedUser.getId(),
                                                "spotifyId", resolvedUser.getSpotifyId(),
                                                "displayName", resolvedUser.getDisplayName() != null ? resolvedUser.getDisplayName() : "",
                                                "avatarUrl", resolvedUser.getAvatarUrl() != null ? resolvedUser.getAvatarUrl() : ""
                    ));
                })
                .orElse(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        // Stateless: the client simply discards its bearer token.
        return ResponseEntity.noContent().build();
    }

        private User syncUserProfile(User user) {
                try {
                        var profile = tokenService.getApiForUser(user.getId()).getCurrentUsersProfile().build().execute();
                        if (profile.getDisplayName() != null && !profile.getDisplayName().isBlank()) {
                                user.setDisplayName(profile.getDisplayName());
                        }
                        if (profile.getImages() != null && profile.getImages().length > 0) {
                                user.setAvatarUrl(profile.getImages()[0].getUrl());
                        }
                        return userRepository.save(user);
                } catch (Exception e) {
                        log.warn("Failed to refresh Spotify profile metadata for user {}", user.getSpotifyId(), e);
                        return user;
        }
        }
}
