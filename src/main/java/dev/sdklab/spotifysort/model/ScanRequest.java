package dev.sdklab.spotifysort.model;

import java.util.List;

import dev.sdklab.spotifysort.tagging.api.ProviderMode;

public record ScanRequest(List<String> sourcePlaylistIds, int threshold, ProviderMode providerMode) {

    /** Backwards-compatible constructor: defaults to LASTFM_ONLY when providerMode is absent. */
    public ScanRequest(List<String> sourcePlaylistIds, int threshold) {
        this(sourcePlaylistIds, threshold, ProviderMode.LASTFM_ONLY);
    }
}
