package dev.sdklab.spotifysort.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonPrimitive;
import dev.sdklab.spotifysort.model.*;
import dev.sdklab.spotifysort.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import se.michaelthelin.spotify.SpotifyApi;
import se.michaelthelin.spotify.model_objects.specification.Playlist;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PlaylistExecutionService {

    private final TokenService tokenService;
    private final UserRepository userRepository;

    public ExecuteSummary execute(Long userId, ExecuteRequest request) throws Exception {
        SpotifyApi api = tokenService.getApiForUser(userId);
        User user = userRepository.findById(userId).orElseThrow();

        int totalTracksAdded = 0;

        // Condition A — add tracks to existing playlists
        for (UpdateAction action : request.updates()) {
            totalTracksAdded += addTracksToPlaylist(api, action.playlistId(), action.trackUris());
        }

        // Condition B — create new playlists and populate them
        for (CreateAction action : request.creates()) {
            Playlist newPlaylist = api.createPlaylist(user.getSpotifyId(), action.playlistName())
                    .public_(false)
                    .build()
                    .execute();
            totalTracksAdded += addTracksToPlaylist(api, newPlaylist.getId(), action.trackUris());
        }

        return new ExecuteSummary(
                request.updates().size(),
                request.creates().size(),
                totalTracksAdded
        );
    }

    private int addTracksToPlaylist(SpotifyApi api, String playlistId, List<String> trackUris) throws Exception {
        int added = 0;
        for (int i = 0; i < trackUris.size(); i += 100) {
            List<String> batch = trackUris.subList(i, Math.min(i + 100, trackUris.size()));

            JsonArray urisJson = new JsonArray();
            batch.forEach(uri -> urisJson.add(new JsonPrimitive(uri)));

            api.addItemsToPlaylist(playlistId, urisJson).build().execute();
            added += batch.size();
        }
        return added;
    }
}
