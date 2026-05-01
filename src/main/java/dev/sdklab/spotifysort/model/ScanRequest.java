package dev.sdklab.spotifysort.model;

import java.util.List;

public record ScanRequest(List<String> sourcePlaylistIds, int threshold) {}
