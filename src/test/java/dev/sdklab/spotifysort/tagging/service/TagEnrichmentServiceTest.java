package dev.sdklab.spotifysort.tagging.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

import dev.sdklab.spotifysort.model.RawTrack;
import dev.sdklab.spotifysort.tagging.api.LlmTagProvider;
import dev.sdklab.spotifysort.tagging.api.LlmTrackResult;
import dev.sdklab.spotifysort.tagging.api.ProviderLookupStatus;
import dev.sdklab.spotifysort.tagging.api.ProviderMode;
import dev.sdklab.spotifysort.tagging.api.TagProvider;
import dev.sdklab.spotifysort.tagging.api.TagResult;
import dev.sdklab.spotifysort.tagging.api.TagSource;
import dev.sdklab.spotifysort.tagging.api.TagType;

@ExtendWith(MockitoExtension.class)
class TagEnrichmentServiceTest {

    @Mock
    private TagProvider tagProvider;

    @Mock
    private TagCacheService tagCacheService;

        @Mock
        private LlmTagProvider llmTagProvider;

    private TagEnrichmentService tagEnrichmentService;

    @BeforeEach
    void setUp() {
        tagEnrichmentService = new TagEnrichmentService(tagProvider, Optional.empty(), tagCacheService);
    }

    @Test
    void enrichTracks_bothMode_withLlm_noTags_usesArtistFallback() {
        RawTrack first = new RawTrack(
                "track-1",
                "Midnight City",
                "spotify:track:1",
                List.of("artist-1"),
                List.of("M83"),
                null
        );
        RawTrack second = new RawTrack(
                "track-2",
                "Midnight City",
                "spotify:track:2",
                List.of("artist-1"),
                List.of("M83"),
                null
        );

        when(tagCacheService.getFreshTrackTagsByTrackIds(List.of("track-1", "track-2"), TagSource.LAST_FM))
                .thenReturn(Map.of());
        when(tagCacheService.getFreshArtistTagsByArtistIds(List.of("artist-1"), TagSource.LAST_FM))
                .thenReturn(Map.of());
        when(tagCacheService.getFreshTrackTagsByTrackIds(List.of("track-1", "track-2"), TagSource.LLM_GPT))
                .thenReturn(Map.of());
        when(tagCacheService.getProviderStatesByTrackIds(List.of("track-1", "track-2"), TagSource.LLM_GPT))
                .thenReturn(Map.of());

        when(tagProvider.getTagsForTrack("M83", "Midnight City")).thenReturn(List.of());

        when(llmTagProvider.getTagsForTracks(List.of(first, second))).thenReturn(List.of(
                new LlmTrackResult("track-1", ProviderLookupStatus.UNKNOWN, List.of(), null, null, 0.0, "gpt-test"),
                new LlmTrackResult("track-2", ProviderLookupStatus.UNKNOWN, List.of(), null, null, 0.0, "gpt-test")
        ));

        TagEnrichmentService svc = new TagEnrichmentService(tagProvider, Optional.of(llmTagProvider), tagCacheService);

        Map<String, Set<String>> enriched = svc.enrichTracks(List.of(first, second), ProviderMode.BOTH, (c, t) -> {});

        // Fallback should use artist name lowercased when providers return no tags
        assertThat(enriched).containsEntry("track-1", Set.of("m83"));
        assertThat(enriched).containsEntry("track-2", Set.of("m83"));

        // Provider state should be persisted for LLM lookups
        verify(tagCacheService).storeProviderState("track-1", TagSource.LLM_GPT, ProviderLookupStatus.UNKNOWN, "gpt-test", null, null, 0.0);
        verify(tagCacheService).storeProviderState("track-2", TagSource.LLM_GPT, ProviderLookupStatus.UNKNOWN, "gpt-test", null, null, 0.0);
    }

    @Test
    void enrichTracks_reusesTrackLookupsWithinSingleBatch() {
        RawTrack first = new RawTrack(
                "track-1",
                "Midnight City",
                "spotify:track:1",
                List.of("artist-1"),
                List.of("M83"),
                null
        );
        RawTrack second = new RawTrack(
                "track-2",
                "Midnight City",
                "spotify:track:2",
                List.of("artist-1"),
                List.of("M83"),
                null
        );
        List<TagResult> providerTags = List.of(
                new TagResult("synthpop", TagType.GENRE, TagSource.LAST_FM, 80)
        );

        when(tagCacheService.getFreshTrackTagsByTrackIds(List.of("track-1", "track-2"), TagSource.LAST_FM))
                .thenReturn(Map.of());
        when(tagCacheService.getFreshArtistTagsByArtistIds(List.of("artist-1"), TagSource.LAST_FM))
                .thenReturn(Map.of());
        when(tagProvider.getTagsForTrack("M83", "Midnight City")).thenReturn(providerTags);

        List<String> progress = new ArrayList<>();
        Map<String, Set<String>> enriched = tagEnrichmentService.enrichTracks(
                List.of(first, second),
                (current, total) -> progress.add(current + "/" + total)
        );

        assertThat(enriched).containsEntry("track-1", Set.of("synthpop"));
        assertThat(enriched).containsEntry("track-2", Set.of("synthpop"));
        assertThat(progress).containsExactly("1/2", "2/2");

        verify(tagProvider, times(1)).getTagsForTrack("M83", "Midnight City");
        verify(tagProvider, never()).getTagsForArtist("M83");
        verify(tagCacheService).storeTrackTags("track-1", TagSource.LAST_FM, providerTags);
        verify(tagCacheService).storeTrackTags("track-2", TagSource.LAST_FM, providerTags);
    }
}