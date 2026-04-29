package dev.sdklab.spotifysort.tagging.service;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import dev.sdklab.spotifysort.model.RawTrack;
import dev.sdklab.spotifysort.tagging.api.TagProvider;
import dev.sdklab.spotifysort.tagging.api.TagResult;
import dev.sdklab.spotifysort.tagging.api.TagSource;

/**
 * Orchestrates tag enrichment for a list of tracks.
 * Consults the DB cache first; falls back to the configured TagProvider,
 * then to bare artist names when the provider returns nothing.
 *
 * <p>This class is the designated microservice boundary: when the tagging
 * subsystem is extracted, replace this implementation with an HTTP client.
 */
@Service
public class TagEnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(TagEnrichmentService.class);

    private final TagProvider tagProvider;
    private final TagCacheService tagCacheService;

    public TagEnrichmentService(TagProvider tagProvider, TagCacheService tagCacheService) {
        this.tagProvider = tagProvider;
        this.tagCacheService = tagCacheService;
    }

    /**
     * Returns a map of Spotify track ID → set of normalized tag values.
     */
    public Map<String, Set<String>> enrichTracks(List<RawTrack> tracks) {
        return enrichTracks(tracks, (current, total) -> {
        });
    }

    public Map<String, Set<String>> enrichTracks(List<RawTrack> tracks, BiConsumer<Integer, Integer> progressListener) {
        Map<TrackLookupKey, List<TagResult>> trackRequestCache = new HashMap<>();
        Map<String, List<TagResult>> artistRequestCache = new HashMap<>();
        Map<String, Set<String>> enrichedByTrackId = new java.util.LinkedHashMap<>();
        int totalTracks = tracks.size();
        int currentTrack = 0;

        for (RawTrack track : tracks) {
            currentTrack += 1;
            Set<String> resolved = resolveTagValues(track, trackRequestCache, artistRequestCache);
            enrichedByTrackId.merge(track.id(), resolved, (left, right) -> {
                Set<String> merged = new LinkedHashSet<>(left);
                merged.addAll(right);
                return merged;
            });
            progressListener.accept(currentTrack, totalTracks);
        }

        return enrichedByTrackId;
    }

    private Set<String> resolveTagValues(
            RawTrack track,
            Map<TrackLookupKey, List<TagResult>> trackRequestCache,
            Map<String, List<TagResult>> artistRequestCache
    ) {
        List<TagResult> tags = resolveTagResults(track, trackRequestCache, artistRequestCache);
        if (tags.isEmpty()) {
            // Last-resort fallback: use artist names as tags
            return track.artistNames().stream()
                    .map(String::toLowerCase)
                    .collect(Collectors.toSet());
        }
        return tags.stream()
                .map(TagResult::value)
                .collect(Collectors.toSet());
    }

    private List<TagResult> resolveTagResults(
            RawTrack track,
            Map<TrackLookupKey, List<TagResult>> trackRequestCache,
            Map<String, List<TagResult>> artistRequestCache
    ) {
        String primaryArtist = track.artistNames().isEmpty() ? "" : track.artistNames().get(0);
        String trackName = track.name() == null ? "" : track.name();

        // 1. Check track-level cache
        if (tagCacheService.isTrackTagsCacheValid(track.id(), TagSource.LAST_FM)) {
            List<TagResult> cached = tagCacheService.getTrackTags(track.id());
            if (!cached.isEmpty()) {
                log.debug("Cache hit (track): {}", track.id());
                return cached;
            }
        }

        // 2. Fetch from provider at track level
        List<TagResult> fromTrack = List.of();
        if (!primaryArtist.isBlank() && !trackName.isBlank()) {
            TrackLookupKey lookupKey = new TrackLookupKey(primaryArtist, trackName);
            fromTrack = trackRequestCache.computeIfAbsent(
                    lookupKey,
                    key -> List.copyOf(tagProvider.getTagsForTrack(key.artistName(), key.trackName()))
            );
        }

        if (!fromTrack.isEmpty()) {
            tagCacheService.storeTrackTags(track.id(), TagSource.LAST_FM, fromTrack);
            return fromTrack;
        }

        // 3. Fetch from provider at artist level (use first artist ID for cache key)
        String firstArtistId = track.artistIds().isEmpty() ? "" : track.artistIds().get(0);
        if (!primaryArtist.isBlank() && !firstArtistId.isBlank()) {
            if (tagCacheService.isArtistTagsCacheValid(firstArtistId, TagSource.LAST_FM)) {
                List<TagResult> cachedArtist = tagCacheService.getArtistTags(firstArtistId);
                if (!cachedArtist.isEmpty()) {
                    log.debug("Cache hit (artist): {}", firstArtistId);
                    return cachedArtist;
                }
            }
            List<TagResult> fromArtist = artistRequestCache.computeIfAbsent(
                    firstArtistId,
                    key -> List.copyOf(tagProvider.getTagsForArtist(primaryArtist))
            );
            if (!fromArtist.isEmpty()) {
                tagCacheService.storeArtistTags(firstArtistId, TagSource.LAST_FM, fromArtist);
                return fromArtist;
            }
        }

        // 4. Provider returned nothing — return empty, caller applies fallback
        return List.of();
    }

    private record TrackLookupKey(String artistName, String trackName) {}
}
