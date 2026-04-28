package dev.sdklab.spotifysort.model;

import java.util.List;

/** Represents tracks that should be added to a playlist the user already owns. */
public record PlaylistUpdate(String playlistId, String playlistName, List<TrackRef> tracks) {}
