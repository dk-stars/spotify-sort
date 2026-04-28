package dev.sdklab.spotifysort.service;

import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import dev.sdklab.spotifysort.model.User;
import dev.sdklab.spotifysort.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import se.michaelthelin.spotify.SpotifyApi;
import se.michaelthelin.spotify.model_objects.credentials.AuthorizationCodeCredentials;

@Service
@RequiredArgsConstructor
@Slf4j
public class TokenService {

    @Value("${spotify.client-id}")
    private String clientId;

    @Value("${spotify.client-secret}")
    private String clientSecret;

    @Value("${spotify.redirect-uri}")
    private String redirectUri;

    private final UserRepository userRepository;

    /**
     * Returns a fully hydrated SpotifyApi instance for the given user,
     * automatically refreshing the access token if it is near expiry.
     */
    public SpotifyApi getApiForUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        if (Instant.now().isAfter(user.getTokenExpiresAt().minusSeconds(60))) {
            refreshToken(user);
        }
        return buildApi(user.getAccessToken(), user.getRefreshToken());
    }

    private SpotifyApi buildApi(String accessToken, String refreshToken) {
        // Only set the access/refresh tokens — do NOT include clientId/clientSecret here.
        // When client credentials are present alongside a bearer token, the library may
        // choose the wrong authentication method for data requests.
        return new SpotifyApi.Builder()
                .setAccessToken(accessToken)
                .setRefreshToken(refreshToken)
                .build();
    }

    private void refreshToken(User user) {
        try {
            SpotifyApi refreshApi = new SpotifyApi.Builder()
                    .setClientId(clientId)
                    .setClientSecret(clientSecret)
                    .setRefreshToken(user.getRefreshToken())
                    .build();

            AuthorizationCodeCredentials credentials =
                    refreshApi.authorizationCodeRefresh().build().execute();

            user.setAccessToken(credentials.getAccessToken());
            user.setTokenExpiresAt(Instant.now().plusSeconds(credentials.getExpiresIn()));
            if (credentials.getRefreshToken() != null) {
                user.setRefreshToken(credentials.getRefreshToken());
            }
            userRepository.save(user);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to refresh Spotify token for user: " + user.getSpotifyId(), e);
        }
    }
}
