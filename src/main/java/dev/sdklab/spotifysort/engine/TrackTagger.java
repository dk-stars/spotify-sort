package dev.sdklab.spotifysort.engine;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import dev.sdklab.spotifysort.model.RawTrack;
import dev.sdklab.spotifysort.model.TaggedTrack;

/**
 * Builds a TaggedTrack from pre-enriched tag data supplied by TagEnrichmentService.
 */
@Component
public class TrackTagger {

    /**
     * @param track       the source track
     * @param enrichedTags map of trackId → set of tag values produced by TagEnrichmentService
     */
    public TaggedTrack tag(RawTrack track, Map<String, Set<String>> enrichedTags) {
        Set<String> tags = new HashSet<>(
                enrichedTags.getOrDefault(track.id(), Set.of())
        );

        // Final safety net: if enrichment produced nothing, use artist names
        if (tags.isEmpty()) {
            track.artistNames().stream()
                    .filter(n -> n != null && !n.isBlank())
                    .map(String::trim)
                    .forEach(tags::add);
        }

        return new TaggedTrack(
            track.id(),
            track.name(),
            track.uri(),
            track.artistNames(),
            track.albumImageUrl(),
            tags
        );
    }
}
