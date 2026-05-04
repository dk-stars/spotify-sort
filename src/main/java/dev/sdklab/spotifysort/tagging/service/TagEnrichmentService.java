package dev.sdklab.spotifysort.tagging.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import dev.sdklab.spotifysort.model.RawTrack;
import dev.sdklab.spotifysort.model.TrackProviderState;
import dev.sdklab.spotifysort.tagging.api.LlmTagProvider;
import dev.sdklab.spotifysort.tagging.api.LlmTrackResult;
import dev.sdklab.spotifysort.tagging.api.ProviderMode;
import dev.sdklab.spotifysort.tagging.api.TagProvider;
import dev.sdklab.spotifysort.tagging.api.TagResult;
import dev.sdklab.spotifysort.tagging.api.TagSource;

/**
 * Orchestrates tag enrichment for a list of tracks.
 *
 * <p>Supports three provider modes:
 * <ul>
 *   <li>{@code LASTFM_ONLY} – consult DB cache then Last.fm, then artist fallback.</li>
 *   <li>{@code LLM_ONLY}    – consult DB cache then LLM batch, no Last.fm.</li>
 *   <li>{@code BOTH}        – Last.fm first; LLM fills remaining untagged tracks in batch.</li>
 * </ul>
 *
 * <p>This class is the designated microservice boundary: when the tagging subsystem is extracted,
 * replace this implementation with an HTTP client.
 */
@Service
public class TagEnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(TagEnrichmentService.class);

    private final TagProvider lastFmProvider;
    private final Optional<LlmTagProvider> llmProvider;
    private final TagCacheService tagCacheService;

    public TagEnrichmentService(
            TagProvider lastFmProvider,
            Optional<LlmTagProvider> llmProvider,
            TagCacheService tagCacheService
    ) {
        this.lastFmProvider = lastFmProvider;
        this.llmProvider = llmProvider;
        this.tagCacheService = tagCacheService;
    }

    /** Enriches using the default LASTFM_ONLY mode. */
    public Map<String, Set<String>> enrichTracks(List<RawTrack> tracks) {
        return enrichTracks(tracks, ProviderMode.LASTFM_ONLY, (c, t) -> {});
    }

    /** Enriches using the default LASTFM_ONLY mode with progress reporting. */
    public Map<String, Set<String>> enrichTracks(List<RawTrack> tracks, BiConsumer<Integer, Integer> progressListener) {
        return enrichTracks(tracks, ProviderMode.LASTFM_ONLY, progressListener);
    }

    public Map<String, Set<String>> enrichTracks(
            List<RawTrack> tracks,
            ProviderMode mode,
            BiConsumer<Integer, Integer> progressListener
    ) {
        // Deduplicate tracks for provider calls but preserve original order in output
        List<RawTrack> uniqueTracks = deduplicateById(tracks);
        List<String> uniqueTrackIds = uniqueTracks.stream().map(RawTrack::id).toList();
        List<String> firstArtistIds = uniqueTracks.stream()
                .map(TagEnrichmentService::firstArtistId)
                .filter(artistId -> artistId != null && !artistId.isBlank())
                .distinct()
                .toList();

        // Per-track result accumulator (track id → resolved tag values)
        Map<String, Set<String>> enrichedByTrackId = new LinkedHashMap<>();
        // Tracks that have been finalized (resolved) and should not be considered for fallback
        Set<String> finalizedTrackIds = new HashSet<>();
        // Tracks that have been "checked" for progress purposes (so UI sees activity)
        Set<String> checkedTrackIds = new HashSet<>();

        Map<String, List<TagResult>> cachedLastFmTrackTags = mode != ProviderMode.LLM_ONLY
                ? tagCacheService.getFreshTrackTagsByTrackIds(uniqueTrackIds, TagSource.LAST_FM)
                : Map.of();
        Map<String, List<TagResult>> cachedLastFmArtistTags = mode != ProviderMode.LLM_ONLY
                ? tagCacheService.getFreshArtistTagsByArtistIds(firstArtistIds, TagSource.LAST_FM)
                : Map.of();
        Map<String, List<TagResult>> cachedLlmTrackTags = mode != ProviderMode.LASTFM_ONLY
                ? tagCacheService.getFreshTrackTagsByTrackIds(uniqueTrackIds, TagSource.LLM_GPT)
                : Map.of();
        Map<String, TrackProviderState> llmProviderStates = mode != ProviderMode.LASTFM_ONLY
                ? tagCacheService.getProviderStatesByTrackIds(uniqueTrackIds, TagSource.LLM_GPT)
                : Map.of();

        int total = uniqueTracks.size();
        int[] current = {0};
        int lastFmResolved = 0;
        int llmCacheResolved = 0;
        int llmResolved = 0;
        int fallbackResolved = 0;
        long startedAt = System.nanoTime();

        // ── Pass 1: Last.fm (skipped in LLM_ONLY) ─────────────────────────
        if (mode != ProviderMode.LLM_ONLY) {
            Map<TrackLookupKey, List<TagResult>> trackRequestCache = new HashMap<>();
            Map<String, List<TagResult>> artistRequestCache = new HashMap<>();

            for (RawTrack track : uniqueTracks) {
                List<TagResult> tags = resolveLastFm(
                        track,
                        trackRequestCache,
                        artistRequestCache,
                        cachedLastFmTrackTags,
                        cachedLastFmArtistTags
                );
                if (!tags.isEmpty()) {
                    enrichedByTrackId.put(track.id(), toTagValues(tags));
                    lastFmResolved += 1;
                    // Mark track as finalized/resolved for later passes
                    markFinalized(track.id(), finalizedTrackIds, checkedTrackIds, current, total, progressListener);
                } else {
                    // Mark as checked so the UI shows activity, but do NOT finalize — allow later providers
                    markChecked(track.id(), checkedTrackIds, current, total, progressListener);
                }
            }
        }

        // ── Pass 2a: Use cached LLM tags from DB (BOTH or LLM_ONLY) ──────
        if (mode != ProviderMode.LASTFM_ONLY) {
            for (RawTrack track : uniqueTracks) {
                if (enrichedByTrackId.containsKey(track.id())) {
                    continue;
                }
                List<TagResult> cachedLlm = cachedLlmTrackTags.get(track.id());
                if (cachedLlm != null && !cachedLlm.isEmpty()) {
                    enrichedByTrackId.put(track.id(), toTagValues(cachedLlm));
                    llmCacheResolved += 1;
                    markFinalized(track.id(), finalizedTrackIds, checkedTrackIds, current, total, progressListener);
                }
            }
        }

        // ── Pass 2b: LLM batch for unresolved tracks (BOTH or LLM_ONLY) ──
        if (mode != ProviderMode.LASTFM_ONLY && llmProvider.isPresent()) {
            Instant now = Instant.now();
            List<RawTrack> unresolved = uniqueTracks.stream()
                    .filter(t -> !enrichedByTrackId.containsKey(t.id()))
                    // Skip tracks with any fresh provider state (negative cache)
                    .filter(t -> {
                        TrackProviderState state = llmProviderStates.get(t.id());
                        if (state == null) {
                            return true;  // No state → needs lookup
                        }
                        // Expired → needs refresh; still fresh → skip (negative or positive cache)
                        return !state.getRefreshAfter().isAfter(now);
                    })
                    .collect(Collectors.toList());

            if (!unresolved.isEmpty()) {
                log.debug("LLM pass: {} unresolved tracks (after cache + negative-cache filter)", unresolved.size());
                List<LlmTrackResult> llmResults = llmProvider.get().getTagsForTracks(unresolved);
                for (LlmTrackResult result : llmResults) {
                    // Persist provider state for every result (HIT, UNKNOWN, AMBIGUOUS, etc.)
                    tagCacheService.storeProviderState(
                            result.trackSpotifyId(), TagSource.LLM_GPT, result.status(),
                            result.modelName(), result.matchStrategy(), result.reasonCode(), result.confidence());

                    if (!result.tags().isEmpty()) {
                        tagCacheService.storeTrackTags(result.trackSpotifyId(), TagSource.LLM_GPT, result.tags());
                        enrichedByTrackId.put(result.trackSpotifyId(), toTagValues(result.tags()));
                        llmResolved += 1;
                        markFinalized(result.trackSpotifyId(), finalizedTrackIds, checkedTrackIds, current, total, progressListener);
                    }
                }
            }
        } else if (mode != ProviderMode.LASTFM_ONLY && llmProvider.isEmpty()) {
            log.warn("ProviderMode {} requested but no LlmTagProvider is configured (OPENAI_API_KEY missing?)", mode);
        }

        // ── Pass 3: Artist-name fallback for still-unresolved tracks ──────
        for (RawTrack track : uniqueTracks) {
            if (!finalizedTrackIds.contains(track.id())) {
                enrichedByTrackId.computeIfAbsent(track.id(), id ->
                        track.artistNames().stream()
                                .map(String::toLowerCase)
                                .collect(Collectors.toSet())
                );
                fallbackResolved += 1;
                markFinalized(track.id(), finalizedTrackIds, checkedTrackIds, current, total, progressListener);
            }
        }

        // ── Build final output (preserving original track order) ──────────
        Map<String, Set<String>> output = new LinkedHashMap<>();
        for (RawTrack track : tracks) {
            Set<String> tags = enrichedByTrackId.get(track.id());
            output.computeIfAbsent(track.id(), id -> new LinkedHashSet<>()).addAll(tags == null ? Set.of() : tags);
        }

        log.info(
                "Tag enrichment finished: mode={}, inputTracks={}, uniqueTracks={}, lastFmResolved={}, llmCacheResolved={}, llmResolved={}, fallbackResolved={}, durationMs={}",
                mode,
                tracks.size(),
                uniqueTracks.size(),
                lastFmResolved,
                llmCacheResolved,
                llmResolved,
                fallbackResolved,
                (System.nanoTime() - startedAt) / 1_000_000
        );

        return output;
    }

    private static void markChecked(
            String trackId,
            Set<String> checkedTrackIds,
            int[] current,
            int total,
            BiConsumer<Integer, Integer> progressListener
    ) {
        if (checkedTrackIds.add(trackId)) {
            current[0] += 1;
            progressListener.accept(current[0], total);
        }
    }

    private static void markFinalized(
            String trackId,
            Set<String> finalizedTrackIds,
            Set<String> checkedTrackIds,
            int[] current,
            int total,
            BiConsumer<Integer, Integer> progressListener
    ) {
        if (finalizedTrackIds.add(trackId)) {
            if (!checkedTrackIds.contains(trackId)) {
                current[0] += 1;
            }
            progressListener.accept(current[0], total);
        }
    }

    // ── Last.fm resolution (per-track with in-memory request dedup) ───────────

    private List<TagResult> resolveLastFm(
            RawTrack track,
            Map<TrackLookupKey, List<TagResult>> trackRequestCache,
            Map<String, List<TagResult>> artistRequestCache,
            Map<String, List<TagResult>> cachedTrackTags,
            Map<String, List<TagResult>> cachedArtistTags
    ) {
        String primaryArtist = track.artistNames().isEmpty() ? "" : track.artistNames().get(0);
        String trackName = track.name() == null ? "" : track.name();

        // 1. DB cache (track-level, Last.fm source)
        List<TagResult> cached = cachedTrackTags.get(track.id());
        if (cached != null && !cached.isEmpty()) {
            log.debug("Cache hit (track/lastfm): {}", track.id());
            return cached;
        }

        // 2. Last.fm track lookup
        if (!primaryArtist.isBlank() && !trackName.isBlank()) {
            TrackLookupKey key = new TrackLookupKey(primaryArtist, trackName);
            List<TagResult> fromTrack = trackRequestCache.computeIfAbsent(
                    key, k -> List.copyOf(lastFmProvider.getTagsForTrack(k.artistName(), k.trackName()))
            );
            if (!fromTrack.isEmpty()) {
                tagCacheService.storeTrackTags(track.id(), TagSource.LAST_FM, fromTrack);
                return fromTrack;
            }
        }

        // 3. DB cache (artist-level, Last.fm source)
        String firstArtistId = track.artistIds().isEmpty() ? "" : track.artistIds().get(0);
        if (!primaryArtist.isBlank() && !firstArtistId.isBlank()) {
            List<TagResult> cachedArtist = cachedArtistTags.get(firstArtistId);
            if (cachedArtist != null && !cachedArtist.isEmpty()) {
                log.debug("Cache hit (artist/lastfm): {}", firstArtistId);
                return cachedArtist;
            }
            // 4. Last.fm artist lookup
            List<TagResult> fromArtist = artistRequestCache.computeIfAbsent(
                    firstArtistId, id -> List.copyOf(lastFmProvider.getTagsForArtist(primaryArtist))
            );
            if (!fromArtist.isEmpty()) {
                tagCacheService.storeArtistTags(firstArtistId, TagSource.LAST_FM, fromArtist);
                return fromArtist;
            }
        }

        return List.of();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Set<String> toTagValues(List<TagResult> tags) {
        return tags.stream().map(TagResult::value).collect(Collectors.toSet());
    }

    private static List<RawTrack> deduplicateById(List<RawTrack> tracks) {
        Map<String, RawTrack> seen = new LinkedHashMap<>();
        for (RawTrack track : tracks) {
            seen.putIfAbsent(track.id(), track);
        }
        return new ArrayList<>(seen.values());
    }

    private static String firstArtistId(RawTrack track) {
        return track.artistIds().isEmpty() ? null : track.artistIds().get(0);
    }

    private static void advanceProgress(
            String trackId,
            Set<String> finalizedTrackIds,
            int[] current,
            int total,
            BiConsumer<Integer, Integer> progressListener
    ) {
        if (!finalizedTrackIds.add(trackId)) {
            return;
        }

        current[0] += 1;
        progressListener.accept(current[0], total);
    }

    private record TrackLookupKey(String artistName, String trackName) {}
}

