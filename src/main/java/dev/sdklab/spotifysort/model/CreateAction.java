package dev.sdklab.spotifysort.model;

import java.util.List;

public record CreateAction(String playlistName, List<String> trackUris) {}
