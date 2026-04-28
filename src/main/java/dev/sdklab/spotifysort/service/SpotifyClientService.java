package dev.sdklab.spotifysort.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

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
import se.michaelthelin.spotify.model_objects.specification.Paging;
import se.michaelthelin.spotify.model_objects.specification.PlaylistSimplified;

/**
 * Thin wrapper around the Spotify Web API that handles pagination and batching.
 * Every method accepts a userId and delegates token management to TokenService.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SpotifyClientService {

    private final TokenService tokenService;
    private final UserRepository userRepository;
    private final RestTemplate restTemplate;

    // -------------------------------------------------------------------------
    // Playlists
    // -------------------------------------------------------------------------

    public List<PlaylistSummary> getUserPlaylists(Long userId) throws Exception {
        User user = userRepository.findById(userId).orElseThrow();
        String spotifyUserId = user.getSpotifyId();
        SpotifyApi api = tokenService.getApiForUser(userId);
        List<PlaylistSummary> result = new ArrayList<>();
        int offset = 0;
        final int limit = 50;

        while (true) {
            Paging<PlaylistSimplified> page = api.getListOfCurrentUsersPlaylists()
                    .limit(limit)
                    .offset(offset)
                    .build()
                    .execute();

            for (PlaylistSimplified p : page.getItems()) {
                // Only include playlists owned by the authenticated user.
                // Followed playlists owned by others may be private → 403 on track fetch.
                if (p.getOwner() == null || !spotifyUserId.equals(p.getOwner().getId())) {
                    log.info("Skipping playlist '{}' (id={}) owned by {} — not owned by current user {}",
                            p.getName(), p.getId(),
                            p.getOwner() != null ? p.getOwner().getId() : "null",
                            spotifyUserId);
                    continue;
                }
                log.info("Including playlist '{}' (id={}) owned by current user", p.getName(), p.getId());
                // Spotify's simplified playlist response no longer reliably includes
                // tracks.total — treat 0/null as unknown rather than actually empty.
                int total = (p.getTracks() != null && p.getTracks().getTotal() != null)
                        ? p.getTracks().getTotal() : -1;
                result.add(new PlaylistSummary(p.getId(), p.getName(), total));
            }

            if (page.getNext() == null) break;
            offset += limit;
        }

        return result;
    }

    public List<RawTrack> getPlaylistTracks(Long userId, String playlistId) throws Exception {
        log.info("Fetching tracks for user {} from playlist {} via /items endpoint", userId, playlistId);
        // Reload user after potential token refresh triggered by getApiForUser
        tokenService.getApiForUser(userId);
        User user = userRepository.findById(userId).orElseThrow();

        List<RawTrack> result = new ArrayList<>();
        String nextUrl = "https://api.spotify.com/v1/playlists/" + playlistId
                + "/items?limit=100&additional_types=track";

        while (nextUrl != null) {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(user.getAccessToken());

            JsonNode body = restTemplate.exchange(
                    nextUrl, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class
            ).getBody();

            log.info("Received response for playlist items request: {}", body);
            log.info("Received response for playlist items request: {}", body);
            if (body == null) break;

            JsonNode items = body.get("items");
            if (items != null && items.isArray()) {
                for (JsonNode item : items) {
                    // Items can have track data nested under "item" field (playlist context)
                    // or under "track" field (search context) or be direct track objects.
                    JsonNode track = item;
                    if (item.has("item") && !item.get("item").isNull()) {
                        track = item.get("item");
                    } else if (item.has("track") && !item.get("track").isNull()) {
                        track = item.get("track");
                    }

                    String id = track.path("id").asText(null);
                    if (id == null || id.isBlank()) continue;

                    String name = track.path("name").asText("");
                    String uri = track.path("uri").asText("spotify:track:" + id);

                    List<String> artistIds = new ArrayList<>();
                    List<String> artistNames = new ArrayList<>();
                    JsonNode artists = track.get("artists");
                    if (artists != null && artists.isArray()) {
                        for (JsonNode artist : artists) {
                            String artistId = artist.path("id").asText(null);
                            if (artistId != null && !artistId.isBlank()) artistIds.add(artistId);
                            String artistName = artist.path("name").asText(null);
                            if (artistName != null && !artistName.isBlank()) artistNames.add(artistName);
                        }
                    }

                    result.add(new RawTrack(id, name, uri, artistIds, artistNames));
                }
            }

            JsonNode next = body.get("next");
            nextUrl = (next != null && !next.isNull()) ? next.asText() : null;
        }

        log.info("Fetched {} tracks from playlist {}", result.size(), playlistId);
        return result;
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
