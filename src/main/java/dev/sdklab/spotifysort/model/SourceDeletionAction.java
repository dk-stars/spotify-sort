package dev.sdklab.spotifysort.model;

import java.util.List;

public record SourceDeletionAction(String sourcePlaylistId, List<String> trackUris) {}