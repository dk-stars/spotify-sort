package dev.sdklab.spotifysort.model;

import java.util.List;

public record UpdateAction(String playlistId, List<String> trackUris) {}
