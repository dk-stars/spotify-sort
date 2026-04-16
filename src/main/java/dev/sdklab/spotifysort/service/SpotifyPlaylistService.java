package dev.sdklab.spotifysort.service;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import se.michaelthelin.spotify.SpotifyApi;
import se.michaelthelin.spotify.model_objects.specification.User;
import se.michaelthelin.spotify.requests.data.users_profile.GetCurrentUsersProfileRequest;

@Service
@RequiredArgsConstructor
public class SpotifyPlaylistService {

    private final SpotifyApi spotifyApi;

    public User getCurrentUserProfile() throws Exception {
        GetCurrentUsersProfileRequest request = spotifyApi.getCurrentUsersProfile().build();
        return request.execute();
    }
}
