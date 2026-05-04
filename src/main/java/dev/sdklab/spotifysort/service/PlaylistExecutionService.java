package dev.sdklab.spotifysort.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.sdklab.spotifysort.model.CreateAction;
import dev.sdklab.spotifysort.model.ExecuteRequest;
import dev.sdklab.spotifysort.model.ExecuteSummary;
import dev.sdklab.spotifysort.model.ScanJob;
import dev.sdklab.spotifysort.model.SourceDeletionAction;
import dev.sdklab.spotifysort.model.UpdateAction;
import dev.sdklab.spotifysort.model.User;
import dev.sdklab.spotifysort.repository.ScanJobRepository;
import dev.sdklab.spotifysort.repository.UserRepository;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PlaylistExecutionService {

    private final TokenService tokenService;
    private final UserRepository userRepository;
    private final ScanJobRepository scanJobRepository;
    private final SpotifyClientService spotifyClientService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public ExecuteSummary execute(Long userId, ExecuteRequest request) throws Exception {
        tokenService.getApiForUser(userId);
        User user = userRepository.findById(userId).orElseThrow();
        String accessToken = user.getAccessToken();

        int totalTracksAdded = 0;
        List<String> createdPlaylistIds = new ArrayList<>();
        List<SourceDeletionAction> sourceDeletionActions = List.of();

        // Condition A — add tracks to existing playlists
        for (UpdateAction action : request.updates()) {
            totalTracksAdded += addTracksToPlaylist(accessToken, action.playlistId(), action.trackUris());
        }

        // Condition B — create new playlists and populate them
        for (CreateAction action : request.creates()) {
            String playlistId = createPlaylist(accessToken, capitalizePlaylistName(action.playlistName()));
            createdPlaylistIds.add(playlistId);
            totalTracksAdded += addTracksToPlaylist(accessToken, playlistId, action.trackUris());
        }

        // Invalidate playlists cache so frontend sees newly created playlists immediately
        if (!createdPlaylistIds.isEmpty()) {
            spotifyClientService.invalidateUserPlaylistsCache(userId);
        }

        ExecuteSummary summary = new ExecuteSummary(
                request.updates().size(),
                request.creates().size(),
                totalTracksAdded
        );

        if (request.scanJobId() != null) {
            if (request.deleteFromSources()) {
                sourceDeletionActions = deleteTracksFromSources(userId, accessToken, request.scanJobId(), collectSelectedTrackUris(request));
            }
            persistExecution(userId, request.scanJobId(), request, summary, createdPlaylistIds, sourceDeletionActions);
        }

        return summary;
    }

    public void undo(Long userId, Long jobId) throws Exception {
        tokenService.getApiForUser(userId);
        User user = userRepository.findById(userId).orElseThrow();
        ScanJob job = scanJobRepository.findByIdAndUserId(jobId, userId).orElseThrow();

        if (!job.isApplied() || job.isUndone()) {
            return;
        }

        ExecuteRequest request = readValue(job.getExecutionRequestJson(), ExecuteRequest.class);
        List<String> createdPlaylistIds = readStringList(job.getCreatedPlaylistIdsJson());
        List<SourceDeletionAction> sourceDeletionActions = readSourceDeletionActions(job.getSourceDeletionActionsJson());

        for (UpdateAction action : request.updates()) {
            removeTracksFromPlaylist(user.getAccessToken(), action.playlistId(), action.trackUris());
        }

        for (String playlistId : createdPlaylistIds) {
            deletePlaylist(user.getAccessToken(), playlistId);
        }

        // Invalidate playlists cache so frontend does not show deleted playlists
        if (!createdPlaylistIds.isEmpty()) {
            spotifyClientService.invalidateUserPlaylistsCache(userId);
        }

        for (SourceDeletionAction action : sourceDeletionActions) {
            restoreTracksToSource(user.getAccessToken(), action.sourcePlaylistId(), action.trackUris());
        }

        job.setUndone(true);
        job.setUndoneAt(Instant.now());
        scanJobRepository.save(job);
    }

    private List<SourceDeletionAction> deleteTracksFromSources(Long userId, String accessToken, Long jobId, Set<String> selectedTrackUris) throws Exception {
        ScanJob job = scanJobRepository.findByIdAndUserId(jobId, userId).orElseThrow();
        List<String> sourcePlaylistIds = resolveSourcePlaylistIds(job);
        List<SourceDeletionAction> actions = new ArrayList<>();

        for (String sourcePlaylistId : sourcePlaylistIds) {
            Set<String> existingTrackUris = spotifyClientService.getPlaylistTrackUris(userId, sourcePlaylistId);
            List<String> trackUrisToDelete = selectedTrackUris.stream()
                    .filter(existingTrackUris::contains)
                    .toList();

            if (trackUrisToDelete.isEmpty()) {
                continue;
            }

            try {
                removeTracksFromSource(accessToken, sourcePlaylistId, trackUrisToDelete);
                actions.add(new SourceDeletionAction(sourcePlaylistId, trackUrisToDelete));
            } catch (IllegalStateException e) {
                // Log permission errors but don't fail the entire operation
                if (e.getMessage() != null && e.getMessage().contains("403")) {
                    // User doesn't have permission to modify this source; skip it gracefully
                    System.err.println("Skipping source playlist " + sourcePlaylistId + ": insufficient permissions");
                } else {
                    // Re-throw other errors
                    throw e;
                }
            }
        }

        return actions;
    }

    private Set<String> collectSelectedTrackUris(ExecuteRequest request) {
        Set<String> selectedTrackUris = new LinkedHashSet<>();
        request.updates().forEach(action -> selectedTrackUris.addAll(action.trackUris()));
        request.creates().forEach(action -> selectedTrackUris.addAll(action.trackUris()));
        return selectedTrackUris;
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

    private void removeTracksFromPlaylist(String accessToken, String playlistId, List<String> trackUris) {
        for (int i = 0; i < trackUris.size(); i += 100) {
            List<String> batch = trackUris.subList(i, Math.min(i + 100, trackUris.size()));
            List<Map<String, String>> items = batch.stream()
                    .map(uri -> Map.of("uri", uri))
                    .toList();

            try {
                restTemplate.exchange(
                        "https://api.spotify.com/v1/playlists/" + playlistId + "/items",
                        HttpMethod.DELETE,
                        new HttpEntity<>(Map.of("items", items), authorizedJsonHeaders(accessToken)),
                        Void.class
                );
            } catch (HttpStatusCodeException e) {
                String errorMsg = formatSpotifyError(e);
                if (e.getStatusCode().value() == 403) {
                    throw new IllegalStateException(
                            "Cannot remove tracks from playlist " + playlistId + ": 403 Forbidden (you may not own this playlist or lack edit permissions)",
                            e
                    );
                }
                throw new IllegalStateException(
                        "Failed to remove tracks from playlist " + playlistId + ": " + errorMsg,
                        e
                );
            }
        }
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

    private void deletePlaylist(String accessToken, String playlistId) {
        try {
            restTemplate.exchange(
                    "https://api.spotify.com/v1/playlists/" + playlistId + "/followers",
                    HttpMethod.DELETE,
                    new HttpEntity<>(authorizedJsonHeaders(accessToken)),
                    Void.class
            );
        } catch (HttpStatusCodeException e) {
            throw new IllegalStateException(
                    "Failed to delete playlist '" + playlistId + "': " + formatSpotifyError(e),
                    e
            );
        }
    }

    private void removeTracksFromSource(String accessToken, String sourcePlaylistId, List<String> trackUris) {
        if (SpotifyClientService.LIKED_SONGS_SOURCE_ID.equals(sourcePlaylistId)) {
            removeTracksFromLikedSongs(accessToken, trackUris);
            return;
        }
        removeTracksFromPlaylist(accessToken, sourcePlaylistId, trackUris);
    }

    private void restoreTracksToSource(String accessToken, String sourcePlaylistId, List<String> trackUris) {
        if (SpotifyClientService.LIKED_SONGS_SOURCE_ID.equals(sourcePlaylistId)) {
            addTracksToLikedSongs(accessToken, trackUris);
            return;
        }
        addTracksToPlaylist(accessToken, sourcePlaylistId, trackUris);
    }

    private void removeTracksFromLikedSongs(String accessToken, List<String> trackUris) {
        // Spotify deprecated /v1/me/tracks — use /v1/me/library with URIs (max 40 per request)
        for (int i = 0; i < trackUris.size(); i += 40) {
            List<String> batch = trackUris.subList(i, Math.min(i + 40, trackUris.size()));
            String urisParam = String.join(",", batch);

            String url = UriComponentsBuilder
                    .fromHttpUrl("https://api.spotify.com/v1/me/library")
                    .queryParam("uris", urisParam)
                    .toUriString();

            try {
                restTemplate.exchange(
                        url,
                        HttpMethod.DELETE,
                        new HttpEntity<>(authorizedJsonHeaders(accessToken)),
                        Void.class
                );
            } catch (HttpStatusCodeException e) {
                throw new IllegalStateException(
                        "Failed to remove tracks from Liked Songs: " + formatSpotifyError(e),
                        e
                );
            }
        }
    }

    private void addTracksToLikedSongs(String accessToken, List<String> trackUris) {
        // Spotify deprecated /v1/me/tracks — use /v1/me/library with URIs (max 40 per request)
        for (int i = 0; i < trackUris.size(); i += 40) {
            List<String> batch = trackUris.subList(i, Math.min(i + 40, trackUris.size()));
            String urisParam = String.join(",", batch);

            String url = UriComponentsBuilder
                    .fromHttpUrl("https://api.spotify.com/v1/me/library")
                    .queryParam("uris", urisParam)
                    .toUriString();

            try {
                restTemplate.exchange(
                        url,
                        HttpMethod.PUT,
                        new HttpEntity<>(authorizedJsonHeaders(accessToken)),
                        Void.class
                );
            } catch (HttpStatusCodeException e) {
                throw new IllegalStateException(
                        "Failed to restore tracks to Liked Songs: " + formatSpotifyError(e),
                        e
                );
            }
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

    private void persistExecution(
            Long userId,
            Long jobId,
            ExecuteRequest request,
            ExecuteSummary summary,
            List<String> createdPlaylistIds,
            List<SourceDeletionAction> sourceDeletionActions
    ) {
        ScanJob job = scanJobRepository.findByIdAndUserId(jobId, userId).orElseThrow();
        job.setExecutionRequestJson(writeValue(request));
        job.setExecutionSummaryJson(writeValue(summary));
        job.setCreatedPlaylistIdsJson(writeValue(createdPlaylistIds));
        job.setSourceDeletionActionsJson(writeValue(sourceDeletionActions));
        job.setApplied(true);
        job.setUndone(false);
        job.setAppliedAt(Instant.now());
        job.setUndoneAt(null);
        scanJobRepository.save(job);
    }

    private List<String> resolveSourcePlaylistIds(ScanJob job) {
        String raw = job.getSourcePlaylistIdsJson();
        if (raw == null || raw.isBlank()) {
            return List.of(job.getSourcePlaylistId());
        }
        return readStringList(raw);
    }

    private List<SourceDeletionAction> readSourceDeletionActions(String raw) {
        try {
            return raw == null || raw.isBlank()
                    ? List.of()
                    : objectMapper.readValue(raw, objectMapper.getTypeFactory().constructCollectionType(List.class, SourceDeletionAction.class));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize source deletion actions", e);
        }
    }

    private <T> T readValue(String raw, Class<T> type) {
        try {
            return objectMapper.readValue(raw, type);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize stored execution payload", e);
        }
    }

    private List<String> readStringList(String raw) {
        try {
            return raw == null || raw.isBlank()
                    ? List.of()
                    : objectMapper.readValue(raw, objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize stored playlist ids", e);
        }
    }

    private String writeValue(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize execution payload", e);
        }
    }

    private String toTrackId(String trackUri) {
        int separatorIndex = trackUri.lastIndexOf(':');
        return separatorIndex >= 0 ? trackUri.substring(separatorIndex + 1) : trackUri;
    }
}
