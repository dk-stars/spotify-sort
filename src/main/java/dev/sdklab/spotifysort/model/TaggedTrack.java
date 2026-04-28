package dev.sdklab.spotifysort.model;

import java.util.Set;

public record TaggedTrack(String trackId, String trackName, String trackUri, Set<String> tags) {}
