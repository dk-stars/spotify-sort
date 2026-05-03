package dev.sdklab.spotifysort.service;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientResponseException;
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
    private static final int PLAYLIST_PAGE_SIZE = 50;
    private static final int TRACK_PAGE_SIZE = 100;
    private static final String PLAYLIST_FIELDS = "items(id,name,owner(id),tracks(total)),next";
    private static final String TRACK_FIELDS = "items(track(id,name,uri,artists(id,name),album(name,release_date,release_date_precision,images(url)),external_ids(isrc),duration_ms,explicit)),next,total";
    private static final String TRACK_URI_FIELDS = "items(track(uri)),next,total";

    private final TokenService tokenService;
    private final UserRepository userRepository;
    private final RestTemplate restTemplate;
    private final Map<PlaylistCacheKey, CachedValue<List<PlaylistSummary>>> userPlaylistsCache = new ConcurrentHashMap<>();

    @Value("${spotify.playlists-cache-ttl-seconds:300}")
    private long playlistsCacheTtlSeconds;

    @Value("${spotify.retry.max-attempts:3}")
    private int retryMaxAttempts;

    @Value("${spotify.retry.default-delay-ms:1000}")
    private long retryDefaultDelayMs;

    // -------------------------------------------------------------------------
    // Playlists
    // -------------------------------------------------------------------------

    public List<PlaylistSummary> getUserPlaylists(Long userId) throws Exception {
        return getUserPlaylists(userId, true);
    }

    public List<PlaylistSummary> getUserPlaylists(Long userId, boolean includeLikedSongs) throws Exception {
        PlaylistCacheKey cacheKey = new PlaylistCacheKey(userId, includeLikedSongs);
        CachedValue<List<PlaylistSummary>> cachedValue = userPlaylistsCache.get(cacheKey);
        if (cachedValue != null && cachedValue.expiresAt().isAfter(Instant.now())) {
            log.debug("Spotify playlists cache hit for user {} (includeLikedSongs={})", userId, includeLikedSongs);
            return cachedValue.value();
        }

        tokenService.getApiForUser(userId);
        User user = userRepository.findById(userId).orElseThrow();
        String spotifyUserId = user.getSpotifyId();
        List<PlaylistSummary> result = new ArrayList<>();
        String nextUrl = buildUserPlaylistsUrl();
        int pageCount = 0;
        long startedAt = System.nanoTime();

        if (includeLikedSongs) {
            fetchLikedSongsSummary(user)
                    .ifPresent(result::add);
        }

        while (nextUrl != null) {
            JsonNode body = spotifyGetJson(nextUrl, user.getAccessToken(), "list playlists");
            pageCount += 1;

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
                        log.debug("Skipping playlist '{}' (id={}) owned by {} — not owned by current user {}",
                                playlistName, playlistId,
                                ownerId,
                                spotifyUserId);
                        continue;
                    }

                    int total = extractPlaylistTrackCount(playlist);
                    log.debug("Including playlist '{}' (id={}) owned by current user with {} tracks",
                            playlistName, playlistId, total);
                    result.add(new PlaylistSummary(playlistId, playlistName, total));
                }
            }

            JsonNode next = body.get("next");
            nextUrl = (next != null && !next.isNull()) ? next.asText() : null;
        }

        List<PlaylistSummary> finalResult = List.copyOf(result);
        userPlaylistsCache.put(cacheKey, new CachedValue<>(
                finalResult,
                Instant.now().plusSeconds(Math.max(1, playlistsCacheTtlSeconds))
        ));

        log.info(
                "Fetched {} playlists for user {} in {} page(s), includeLikedSongs={}, durationMs={}",
                finalResult.size(),
                userId,
                pageCount,
                includeLikedSongs,
                (System.nanoTime() - startedAt) / 1_000_000
        );

        return finalResult;
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
                buildSavedTracksUrl(PLAYLIST_PAGE_SIZE, TRACK_FIELDS),
                LIKED_SONGS_NAME,
                progressListener
            );
        }

        log.info("Fetching tracks for user {} from playlist {} via /items endpoint", userId, playlistId);
        return fetchTracks(
                user.getAccessToken(),
                buildPlaylistTracksUrl(playlistId, TRACK_PAGE_SIZE),
            playlistId,
            progressListener
        );
    }

    public Set<String> getPlaylistTrackUris(Long userId, String playlistId) throws Exception {
        tokenService.getApiForUser(userId);
        User user = userRepository.findById(userId).orElseThrow();

        if (LIKED_SONGS_SOURCE_ID.equals(playlistId)) {
            return fetchTrackUris(user.getAccessToken(), buildSavedTracksUrl(PLAYLIST_PAGE_SIZE, TRACK_URI_FIELDS), LIKED_SONGS_NAME);
        }

        return fetchTrackUris(user.getAccessToken(), buildPlaylistTracksUrl(playlistId, TRACK_PAGE_SIZE), playlistId);
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
        long startedAt = System.nanoTime();

        while (nextUrl != null) {
            currentRequest += 1;
            JsonNode body = spotifyGetJson(nextUrl, accessToken, "load tracks from " + sourceLabel);

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

        log.info(
                "Fetched {} tracks from {} in {} request(s), durationMs={}",
                result.size(),
                sourceLabel,
                Math.max(totalRequests, currentRequest),
                (System.nanoTime() - startedAt) / 1_000_000
        );
        return result;
    }

    private Set<String> fetchTrackUris(String accessToken, String initialUrl, String sourceLabel) {
        Set<String> result = new LinkedHashSet<>();
        String nextUrl = initialUrl;
        int currentRequest = 0;
        int totalRequests = 0;
        long startedAt = System.nanoTime();

        while (nextUrl != null) {
            currentRequest += 1;
            JsonNode body = spotifyGetJson(nextUrl, accessToken, "load track URIs from " + sourceLabel);

            if (body == null) {
                break;
            }

            totalRequests = Math.max(totalRequests, estimateTotalRequests(body, nextUrl));

            JsonNode items = body.get("items");
            if (items != null && items.isArray()) {
                for (JsonNode item : items) {
                    String uri = toTrackUri(item);
                    if (uri != null && !uri.isBlank()) {
                        result.add(uri);
                    }
                }
            }

            JsonNode next = body.get("next");
            nextUrl = (next != null && !next.isNull()) ? next.asText() : null;
        }

        log.info(
                "Fetched {} track URIs from {} in {} request(s), durationMs={}",
                result.size(),
                sourceLabel,
                Math.max(totalRequests, currentRequest),
                (System.nanoTime() - startedAt) / 1_000_000
        );
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

        JsonNode album = track.path("album");
        String albumName = album.path("name").asText(null);
        String releaseDate = album.path("release_date").asText(null);
        String releaseDatePrecision = album.path("release_date_precision").asText(null);
        String isrc = track.path("external_ids").path("isrc").asText(null);
        long durationMs = track.path("duration_ms").asLong(0);
        boolean explicit = track.path("explicit").asBoolean(false);

        return new RawTrack(id, name, uri, artistIds, artistNames, extractAlbumImageUrl(track),
                albumName, releaseDate, releaseDatePrecision, isrc, durationMs, explicit);
    }

    private String toTrackUri(JsonNode item) {
        JsonNode track = item;
        if (item.has("item") && !item.get("item").isNull()) {
            track = item.get("item");
        } else if (item.has("track") && !item.get("track").isNull()) {
            track = item.get("track");
        }

        String uri = track.path("uri").asText(null);
        if (uri != null && !uri.isBlank()) {
            return uri;
        }

        String id = track.path("id").asText(null);
        return (id == null || id.isBlank()) ? null : "spotify:track:" + id;
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
            JsonNode body = spotifyGetJson(buildSavedTracksUrl(1, "total"), user.getAccessToken(), "load liked songs summary");

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

    private String buildUserPlaylistsUrl() {
        // Note: /v1/me/playlists does NOT support the 'fields' parameter
        // (only /v1/playlists/{id} and /v1/playlists/{id}/tracks do).
        // Sending it causes some playlist items to lose tracks.total.
        return UriComponentsBuilder.fromHttpUrl("https://api.spotify.com/v1/me/playlists")
                .queryParam("limit", PLAYLIST_PAGE_SIZE)
                .toUriString();
    }

    private String buildSavedTracksUrl(int limit, String fields) {
        return UriComponentsBuilder.fromHttpUrl("https://api.spotify.com/v1/me/tracks")
                .queryParam("limit", limit)
                .queryParam("fields", fields)
                .toUriString();
    }

    private String buildPlaylistTracksUrl(String playlistId, int limit) {
        return UriComponentsBuilder.fromHttpUrl("https://api.spotify.com/v1/playlists/{playlistId}/items")
                .queryParam("limit", limit)
                .queryParam("additional_types", "track")
                .buildAndExpand(playlistId)
                .toUriString();
    }

    private JsonNode spotifyGetJson(String requestUrl, String accessToken, String operation) {
        URI uri = URI.create(requestUrl);
        String path = uri.getPath();

        for (int attempt = 1; attempt <= retryMaxAttempts; attempt++) {
            long startedAt = System.nanoTime();
            try {
                JsonNode body = restTemplate.exchange(
                        requestUrl,
                        HttpMethod.GET,
                        new HttpEntity<>(authorizedHeaders(accessToken)),
                        JsonNode.class
                ).getBody();
                log.debug(
                        "Spotify GET success: operation='{}', path='{}', attempt={}, durationMs={}",
                        operation,
                        path,
                        attempt,
                        (System.nanoTime() - startedAt) / 1_000_000
                );
                return body;
            } catch (HttpClientErrorException.TooManyRequests e) {
                long delayMs = extractRetryDelayMs(e, attempt);
                log.warn(
                        "Spotify GET rate limited: operation='{}', path='{}', attempt={}, retryDelayMs={}",
                        operation,
                        path,
                        attempt,
                        delayMs
                );
                if (attempt >= retryMaxAttempts) {
                    throw e;
                }
                sleep(delayMs);
            } catch (RestClientResponseException e) {
                log.warn(
                        "Spotify GET failed: operation='{}', path='{}', attempt={}, status={}, response='{}'",
                        operation,
                        path,
                        attempt,
                        e.getRawStatusCode(),
                        abbreviate(e.getResponseBodyAsString())
                );
                throw e;
            }
        }

        throw new IllegalStateException("Spotify request retry loop exited unexpectedly for " + operation);
    }

    private long extractRetryDelayMs(HttpClientErrorException.TooManyRequests exception, int attempt) {
        String retryAfter = exception.getResponseHeaders() != null
                ? exception.getResponseHeaders().getFirst("Retry-After")
                : null;
        if (retryAfter != null) {
            try {
                return Math.max(250L, Long.parseLong(retryAfter) * 1000L);
            } catch (NumberFormatException ignored) {
                // Fall back to the configured backoff when the header is not a numeric second value.
            }
        }
        return Math.max(250L, retryDefaultDelayMs * attempt);
    }

    private void sleep(long delayMs) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry Spotify API request", e);
        }
    }

    private String abbreviate(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.length() <= 240 ? value : value.substring(0, 240) + "...";
    }

    private record PlaylistCacheKey(Long userId, boolean includeLikedSongs) {}

    private record CachedValue<T>(T value, Instant expiresAt) {}

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
