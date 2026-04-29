package dev.sdklab.spotifysort.service;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import dev.sdklab.spotifysort.model.CreateAction;
import dev.sdklab.spotifysort.model.ExecuteRequest;
import dev.sdklab.spotifysort.model.ExecuteSummary;
import dev.sdklab.spotifysort.model.UpdateAction;
import dev.sdklab.spotifysort.model.User;
import dev.sdklab.spotifysort.repository.UserRepository;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PlaylistExecutionService {

    private final TokenService tokenService;
    private final UserRepository userRepository;
    private final RestTemplate restTemplate;

    public ExecuteSummary execute(Long userId, ExecuteRequest request) throws Exception {
        tokenService.getApiForUser(userId);
        User user = userRepository.findById(userId).orElseThrow();
        String accessToken = user.getAccessToken();

        int totalTracksAdded = 0;

        // Condition A — add tracks to existing playlists
        for (UpdateAction action : request.updates()) {
            totalTracksAdded += addTracksToPlaylist(accessToken, action.playlistId(), action.trackUris());
        }

        // Condition B — create new playlists and populate them
        for (CreateAction action : request.creates()) {
            String playlistId = createPlaylist(accessToken, capitalizePlaylistName(action.playlistName()));
            totalTracksAdded += addTracksToPlaylist(accessToken, playlistId, action.trackUris());
        }

        return new ExecuteSummary(
                request.updates().size(),
                request.creates().size(),
                totalTracksAdded
        );
    }

    private int addTracksToPlaylist(String accessToken, String playlistId, List<String> trackUris) {
        int added = 0;
        for (int i = 0; i < trackUris.size(); i += 100) {
            List<String> batch = trackUris.subList(i, Math.min(i + 100, trackUris.size()));

            try {
                restTemplate.exchange(
                    "https://api.spotify.com/v1/playlists/" + playlistId + "/items",
                        HttpMethod.POST,
                        new HttpEntity<>(Map.of("uris", batch), authorizedJsonHeaders(accessToken)),
                        Void.class
                );
            } catch (HttpStatusCodeException e) {
                if (e.getStatusCode().value() == 403) {
                    throw new IllegalStateException(
                        "Spotify rejected adding tracks to playlist " + playlistId
                            + ". If this is a public playlist, log out and log back in so the app can request playlist-modify-public.",
                        e
                    );
                }
                throw new IllegalStateException(
                        "Failed to add tracks to playlist " + playlistId + ": " + formatSpotifyError(e),
                        e
                );
            }
            added += batch.size();
        }
        return added;
    }

    @SuppressWarnings("unchecked")
    private String createPlaylist(String accessToken, String playlistName) {
        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    "https://api.spotify.com/v1/me/playlists",
                    HttpMethod.POST,
                    new HttpEntity<>(
                            Map.of(
                                    "name", playlistName,
                                    "public", false
                            ),
                            authorizedJsonHeaders(accessToken)
                    ),
                    Map.class
            );
            Object playlistId = response.getBody() != null ? response.getBody().get("id") : null;
            if (!(playlistId instanceof String id) || id.isBlank()) {
                throw new IllegalStateException("Spotify returned no playlist id for '" + playlistName + "'");
            }
            return id;
        } catch (HttpStatusCodeException e) {
            throw new IllegalStateException(
                    "Failed to create playlist '" + playlistName + "': " + formatSpotifyError(e),
                    e
            );
        }
    }

    private HttpHeaders authorizedJsonHeaders(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private String formatSpotifyError(HttpStatusCodeException e) {
        String body = e.getResponseBodyAsString();
        if (body == null || body.isBlank()) {
            return e.getStatusCode().value() + " " + e.getStatusText();
        }
        return e.getStatusCode().value() + " " + body;
    }

    private String capitalizePlaylistName(String playlistName) {
        if (playlistName == null || playlistName.isBlank()) {
            return playlistName;
        }
        return Character.toUpperCase(playlistName.charAt(0)) + playlistName.substring(1);
    }
}
