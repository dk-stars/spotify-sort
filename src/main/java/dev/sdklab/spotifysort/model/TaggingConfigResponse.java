package dev.sdklab.spotifysort.model;

import java.util.List;

import dev.sdklab.spotifysort.tagging.api.ProviderMode;

public record TaggingConfigResponse(
        List<ProviderMode> availableModes,
        ProviderMode defaultMode,
        boolean llmConfigured
) {}
