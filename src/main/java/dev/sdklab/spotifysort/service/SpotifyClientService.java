package dev.sdklab.spotifysort.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import dev.sdklab.spotifysort.model.PlaylistSummary;
import lombok.RequiredArgsConstructor;
import se.michaelthelin.spotify.SpotifyApi;
import se.michaelthelin.spotify.model_objects.specification.Artist;
import se.michaelthelin.spotify.model_objects.specification.AudioFeatures;
import se.michaelthelin.spotify.model_objects.specification.Paging;
import se.michaelthelin.spotify.model_objects.specification.PlaylistSimplified;
import se.michaelthelin.spotify.model_objects.specification.PlaylistTrack;
import se.michaelthelin.spotify.model_objects.specification.Track;

/**
 * Thin wrapper around the Spotify Web API that handles pagination and batching.
 * Every method accepts a userId and delegates token management to TokenService.
 */
@Service
@RequiredArgsConstructor
public class SpotifyClientService {

    private final TokenService tokenService;

    // -------------------------------------------------------------------------
    // Playlists
    // -------------------------------------------------------------------------

    public List<PlaylistSummary> getUserPlaylists(Long userId) throws Exception {
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
                int total = p.getTracks() != null ? p.getTracks().getTotal() : 0;
                result.add(new PlaylistSummary(p.getId(), p.getName(), total));
            }

            if (page.getNext() == null) break;
            offset += limit;
        }

        return result;
    }

    public List<Track> getPlaylistTracks(Long userId, String playlistId) throws Exception {
        SpotifyApi api = tokenService.getApiForUser(userId);
        List<Track> result = new ArrayList<>();
        int offset = 0;
        final int limit = 100;

        while (true) {
            Paging<PlaylistTrack> page = api.getPlaylistsItems(playlistId)
                    .limit(limit)
                    .offset(offset)
                    .build()
                    .execute();

            for (PlaylistTrack pt : page.getItems()) {
                if (pt.getTrack() instanceof Track track) {
                    result.add(track);
                }
            }

            if (page.getNext() == null) break;
            offset += limit;
        }

        return result;
    }

    // -------------------------------------------------------------------------
    // Artists (batched, max 50 per request)
    // -------------------------------------------------------------------------

    public Map<String, Artist> getArtistsByIds(Long userId, Set<String> artistIds) throws Exception {
        SpotifyApi api = tokenService.getApiForUser(userId);
        Map<String, Artist> result = new HashMap<>();
        List<String> idList = new ArrayList<>(artistIds);

        for (int i = 0; i < idList.size(); i += 50) {
            String[] batch = idList.subList(i, Math.min(i + 50, idList.size()))
                    .toArray(String[]::new);
            Artist[] artists = api.getSeveralArtists(batch).build().execute();
            for (Artist a : artists) {
                if (a != null) result.put(a.getId(), a);
            }
        }

        return result;
    }

    // -------------------------------------------------------------------------
    // Audio features (batched, max 100 per request)
    // -------------------------------------------------------------------------

    public Map<String, AudioFeatures> getAudioFeatures(Long userId, List<String> trackIds) throws Exception {
        SpotifyApi api = tokenService.getApiForUser(userId);
        Map<String, AudioFeatures> result = new HashMap<>();

        for (int i = 0; i < trackIds.size(); i += 100) {
            String[] batch = trackIds.subList(i, Math.min(i + 100, trackIds.size()))
                    .toArray(String[]::new);
            AudioFeatures[] features = api.getAudioFeaturesForSeveralTracks(batch).build().execute();
            for (AudioFeatures af : features) {
                if (af != null) result.put(af.getId(), af);
            }
        }

        return result;
    }
}
