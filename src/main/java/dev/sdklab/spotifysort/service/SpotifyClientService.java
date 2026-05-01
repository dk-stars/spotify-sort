package dev.sdklab.spotifysort.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.JsonNode;

import dev.sdklab.spotifysort.model.PlaylistSummary;
import dev.sdklab.spotifysort.model.RawArtist;
import dev.sdklab.spotifysort.model.RawTrack;
import dev.sdklab.spotifysort.model.User;
import dev.sdklab.spotifysort.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import se.michaelthelin.spotify.SpotifyApi;
import se.michaelthelin.spotify.model_objects.specification.AudioFeatures;

/**
 * Thin wrapper around the Spotify Web API that handles pagination and batching.
 * Every method accepts a userId and delegates token management to TokenService.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SpotifyClientService {

    public static final String LIKED_SONGS_SOURCE_ID = "__liked_songs__";
    private static final String LIKED_SONGS_NAME = "Liked Songs";

    private final TokenService tokenService;
    private final UserRepository userRepository;
    private final RestTemplate restTemplate;

    // -------------------------------------------------------------------------
    // Playlists
    // -------------------------------------------------------------------------

    public List<PlaylistSummary> getUserPlaylists(Long userId) throws Exception {
        tokenService.getApiForUser(userId);
        User user = userRepository.findById(userId).orElseThrow();
        String spotifyUserId = user.getSpotifyId();
        List<PlaylistSummary> result = new ArrayList<>();
        String nextUrl = "https://api.spotify.com/v1/me/playlists?limit=50";

        fetchLikedSongsSummary(user)
                .ifPresent(result::add);

        while (nextUrl != null) {
            JsonNode body = restTemplate.exchange(
                    nextUrl,
                    HttpMethod.GET,
                    new HttpEntity<>(authorizedHeaders(user.getAccessToken())),
                    JsonNode.class
            ).getBody();

            if (body == null) {
                break;
            }

            JsonNode items = body.path("items");
            if (items.isArray()) {
                for (JsonNode playlist : items) {
                    String playlistId = playlist.path("id").asText(null);
                    if (playlistId == null || playlistId.isBlank()) {
                        continue;
                    }

                    String ownerId = playlist.path("owner").path("id").asText(null);
                    String playlistName = playlist.path("name").asText(playlistId);

                // Only include playlists owned by the authenticated user.
                // Followed playlists owned by others may be private → 403 on track fetch.
                    if (ownerId == null || !spotifyUserId.equals(ownerId)) {
                    log.info("Skipping playlist '{}' (id={}) owned by {} — not owned by current user {}",
                            playlistName, playlistId,
                            ownerId,
                            spotifyUserId);
                    continue;
                }

                    int total = extractPlaylistTrackCount(playlist);
                    log.info("Including playlist '{}' (id={}) owned by current user with {} tracks",
                            playlistName, playlistId, total);
                    result.add(new PlaylistSummary(playlistId, playlistName, total));
                }
            }

            JsonNode next = body.get("next");
            nextUrl = (next != null && !next.isNull()) ? next.asText() : null;
        }

        return result;
    }

    private int extractPlaylistTrackCount(JsonNode playlist) {
        JsonNode itemsTotal = playlist.path("items").path("total");
        if (itemsTotal.canConvertToInt()) {
            return itemsTotal.asInt();
        }

        JsonNode tracksTotal = playlist.path("tracks").path("total");
        if (tracksTotal.canConvertToInt()) {
            return tracksTotal.asInt();
        }

        return -1;
    }

    public List<RawTrack> getPlaylistTracks(Long userId, String playlistId) throws Exception {
        return getPlaylistTracks(userId, playlistId, (currentRequest, totalRequests) -> {
        });
        }

        public List<RawTrack> getPlaylistTracks(
            Long userId,
            String playlistId,
            BiConsumer<Integer, Integer> progressListener
        ) throws Exception {
        tokenService.getApiForUser(userId);
        User user = userRepository.findById(userId).orElseThrow();

        if (LIKED_SONGS_SOURCE_ID.equals(playlistId)) {
            log.info("Fetching saved tracks for user {} from Liked Songs", userId);
            return fetchTracks(
                user.getAccessToken(),
                "https://api.spotify.com/v1/me/tracks?limit=50",
                LIKED_SONGS_NAME,
                progressListener
            );
        }

        log.info("Fetching tracks for user {} from playlist {} via /items endpoint", userId, playlistId);
        return fetchTracks(
                user.getAccessToken(),
                "https://api.spotify.com/v1/playlists/" + playlistId + "/items?limit=100&additional_types=track",
            playlistId,
            progressListener
        );
    }

    public Set<String> getPlaylistTrackUris(Long userId, String playlistId) throws Exception {
        if (LIKED_SONGS_SOURCE_ID.equals(playlistId)) {
            return getPlaylistTracks(userId, playlistId).stream()
                    .map(RawTrack::uri)
                    .collect(Collectors.toSet());
        }

        List<RawTrack> tracks = getPlaylistTracks(userId, playlistId);
        return tracks.stream()
                .map(RawTrack::uri)
                .collect(Collectors.toSet());
    }

    private List<RawTrack> fetchTracks(
            String accessToken,
            String initialUrl,
            String sourceLabel,
            BiConsumer<Integer, Integer> progressListener
    ) {
        List<RawTrack> result = new ArrayList<>();
        String nextUrl = initialUrl;
        int currentRequest = 0;
        int totalRequests = 0;

        while (nextUrl != null) {
            currentRequest += 1;
            JsonNode body = restTemplate.exchange(
                    nextUrl,
                    HttpMethod.GET,
                    new HttpEntity<>(authorizedHeaders(accessToken)),
                    JsonNode.class
            ).getBody();

            if (body == null) {
                progressListener.accept(currentRequest, Math.max(totalRequests, currentRequest));
                break;
            }

            totalRequests = Math.max(totalRequests, estimateTotalRequests(body, nextUrl));
            progressListener.accept(currentRequest, Math.max(totalRequests, currentRequest));

            JsonNode items = body.get("items");
            if (items != null && items.isArray()) {
                for (JsonNode item : items) {
                    RawTrack rawTrack = toRawTrack(item);
                    if (rawTrack != null) {
                        result.add(rawTrack);
                    }
                }
            }

            JsonNode next = body.get("next");
            nextUrl = (next != null && !next.isNull()) ? next.asText() : null;
        }

        log.info("Fetched {} tracks from {}", result.size(), sourceLabel);
        return result;
    }

    private int estimateTotalRequests(JsonNode body, String requestUrl) {
        int totalItems = body.path("total").asInt(-1);
        int requestLimit = extractRequestLimit(requestUrl);

        if (totalItems <= 0 || requestLimit <= 0) {
            return 1;
        }

        return Math.max(1, (int) Math.ceil((double) totalItems / requestLimit));
    }

    private int extractRequestLimit(String requestUrl) {
        String limitValue = UriComponentsBuilder.fromUriString(requestUrl)
                .build()
                .getQueryParams()
                .getFirst("limit");

        if (limitValue == null || limitValue.isBlank()) {
            return 50;
        }

        try {
            return Integer.parseInt(limitValue);
        } catch (NumberFormatException ignored) {
            return 50;
        }
    }

    private RawTrack toRawTrack(JsonNode item) {
        JsonNode track = item;
        if (item.has("item") && !item.get("item").isNull()) {
            track = item.get("item");
        } else if (item.has("track") && !item.get("track").isNull()) {
            track = item.get("track");
        }

        String id = track.path("id").asText(null);
        if (id == null || id.isBlank()) {
            return null;
        }

        String name = track.path("name").asText("");
        String uri = track.path("uri").asText("spotify:track:" + id);

        List<String> artistIds = new ArrayList<>();
        List<String> artistNames = new ArrayList<>();
        JsonNode artists = track.get("artists");
        if (artists != null && artists.isArray()) {
            for (JsonNode artist : artists) {
                String artistId = artist.path("id").asText(null);
                if (artistId != null && !artistId.isBlank()) {
                    artistIds.add(artistId);
                }
                String artistName = artist.path("name").asText(null);
                if (artistName != null && !artistName.isBlank()) {
                    artistNames.add(artistName);
                }
            }
        }

        return new RawTrack(id, name, uri, artistIds, artistNames, extractAlbumImageUrl(track));
    }

    private String extractAlbumImageUrl(JsonNode track) {
        JsonNode images = track.path("album").path("images");
        if (!images.isArray() || images.isEmpty()) {
            return null;
        }
        return images.get(0).path("url").asText(null);
    }

    private HttpHeaders authorizedHeaders(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        return headers;
    }

    private java.util.Optional<PlaylistSummary> fetchLikedSongsSummary(User user) {
        try {
            JsonNode body = restTemplate.exchange(
                    "https://api.spotify.com/v1/me/tracks?limit=1",
                    HttpMethod.GET,
                    new HttpEntity<>(authorizedHeaders(user.getAccessToken())),
                    JsonNode.class
            ).getBody();

            if (body == null) {
                return java.util.Optional.empty();
            }

            return java.util.Optional.of(new PlaylistSummary(
                    LIKED_SONGS_SOURCE_ID,
                    LIKED_SONGS_NAME,
                    body.path("total").asInt(-1)
            ));
        } catch (Exception e) {
            log.warn("Liked Songs unavailable ({}). Re-auth may be required for user-library-read scope.", e.getMessage());
            return java.util.Optional.empty();
        }
    }

    // -------------------------------------------------------------------------
    // Artists (batched, max 50 per request)
    // -------------------------------------------------------------------------

    public Map<String, RawArtist> getArtistsByIds(Long userId, Set<String> artistIds) throws Exception {
        log.info("Fetching {} artists via /v1/artists endpoint", artistIds.size());
        tokenService.getApiForUser(userId);
        User user = userRepository.findById(userId).orElseThrow();

        Map<String, RawArtist> result = new HashMap<>();
        List<String> idList = new ArrayList<>(artistIds);

        try {
            for (int i = 0; i < idList.size(); i += 50) {
                List<String> batch = idList.subList(i, Math.min(i + 50, idList.size()));
                String idsParam = String.join(",", batch);

                HttpHeaders headers = new HttpHeaders();
                headers.setBearerAuth(user.getAccessToken());

                JsonNode body = restTemplate.exchange(
                        "https://api.spotify.com/v1/artists?ids=" + idsParam,
                        HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class
                ).getBody();

                if (body == null) continue;

                JsonNode artists = body.get("artists");
                if (artists != null && artists.isArray()) {
                    for (JsonNode artist : artists) {
                        if (artist == null || artist.isNull()) continue;
                        String id = artist.path("id").asText(null);
                        if (id == null || id.isBlank()) continue;
                        String name = artist.path("name").asText("");
                        List<String> genres = new ArrayList<>();
                        JsonNode genreNode = artist.get("genres");
                        if (genreNode != null && genreNode.isArray()) {
                            for (JsonNode g : genreNode) genres.add(g.asText());
                        }
                        result.put(id, new RawArtist(id, name, genres));
                    }
                }
            }
        } catch (Exception e) {
            // /v1/artists is deprecated for apps created after Feb 2026 — returns 403.
            // Return empty map; caller will fall back to artist names embedded in track data.
            log.warn("Artist genres unavailable ({}), falling back to artist names as tags.", e.getMessage());
        }

        log.info("Fetched {} artists", result.size());
        return result;
    }

    // -------------------------------------------------------------------------
    // Audio features (batched, max 100 per request)
    // -------------------------------------------------------------------------

    public Map<String, AudioFeatures> getAudioFeatures(Long userId, List<String> trackIds) throws Exception {
        SpotifyApi api = tokenService.getApiForUser(userId);
        Map<String, AudioFeatures> result = new HashMap<>();

        try {
            for (int i = 0; i < trackIds.size(); i += 100) {
                String[] batch = trackIds.subList(i, Math.min(i + 100, trackIds.size()))
                        .toArray(String[]::new);
                AudioFeatures[] features = api.getAudioFeaturesForSeveralTracks(batch).build().execute();
                for (AudioFeatures af : features) {
                    if (af != null) result.put(af.getId(), af);
                }
            }
        } catch (Exception e) {
            // /v1/audio-features is deprecated for apps created after Nov 2024 — returns 403.
            // Degrade gracefully: mood tags will be skipped, genre tags still applied.
            log.warn("Audio features unavailable ({}), mood tagging will be skipped.", e.getMessage());
        }

        return result;
    }
}
