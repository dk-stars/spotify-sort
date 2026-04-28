package dev.sdklab.spotifysort.model;

import java.util.List;

/** Represents a tag with enough tracks to warrant creating a new playlist. */
public record PlaylistIdea(String tag, List<TrackRef> tracks) {}
