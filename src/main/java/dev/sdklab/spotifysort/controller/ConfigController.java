package dev.sdklab.spotifysort.controller;

import java.util.List;
import java.util.Optional;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.sdklab.spotifysort.model.TaggingConfigResponse;
import dev.sdklab.spotifysort.tagging.api.LlmTagProvider;
import dev.sdklab.spotifysort.tagging.api.ProviderMode;

@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final boolean llmConfigured;

    public ConfigController(Optional<LlmTagProvider> llmTagProvider) {
        this.llmConfigured = llmTagProvider.isPresent();
    }

    @GetMapping("/tagging")
    public ResponseEntity<TaggingConfigResponse> tagging() {
        List<ProviderMode> available = llmConfigured
                ? List.of(ProviderMode.LASTFM_ONLY, ProviderMode.BOTH, ProviderMode.LLM_ONLY)
                : List.of(ProviderMode.LASTFM_ONLY);

        ProviderMode defaultMode = llmConfigured ? ProviderMode.BOTH : ProviderMode.LASTFM_ONLY;

        return ResponseEntity.ok(new TaggingConfigResponse(available, defaultMode, llmConfigured));
    }
}
