package dev.sdklab.spotifysort.tagging.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.Mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

import dev.sdklab.spotifysort.model.RawTrack;
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

    private TagEnrichmentService tagEnrichmentService;

    @BeforeEach
    void setUp() {
        tagEnrichmentService = new TagEnrichmentService(tagProvider, tagCacheService);
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

        when(tagCacheService.isTrackTagsCacheValid(anyString(), eq(TagSource.LAST_FM))).thenReturn(false);
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