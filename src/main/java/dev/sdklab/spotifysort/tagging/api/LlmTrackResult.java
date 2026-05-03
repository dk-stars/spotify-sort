package dev.sdklab.spotifysort.tagging.api;

import java.util.List;

/**
 * Full result from one LLM lookup for a single track.
 * Carries both the tag list and the provider-state metadata needed for negative caching.
 */
public record LlmTrackResult(
        String trackSpotifyId,
        ProviderLookupStatus status,
        List<TagResult> tags,
        String matchStrategy,
        String reasonCode,
        double confidence,
        String modelName
) {}
