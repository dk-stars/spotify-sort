package dev.sdklab.spotifysort.tagging.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.sdklab.spotifysort.model.ArtistTag;
import dev.sdklab.spotifysort.model.TrackProviderState;
import dev.sdklab.spotifysort.model.TrackTag;
import dev.sdklab.spotifysort.repository.ArtistTagRepository;
import dev.sdklab.spotifysort.repository.TrackProviderStateRepository;
import dev.sdklab.spotifysort.repository.TrackTagRepository;
import dev.sdklab.spotifysort.tagging.api.ProviderLookupStatus;
import dev.sdklab.spotifysort.tagging.api.TagResult;
import dev.sdklab.spotifysort.tagging.api.TagSource;
import dev.sdklab.spotifysort.tagging.norm.TagNormalizer;

@Service
public class TagCacheService {

    private final TrackTagRepository trackTagRepository;
    private final ArtistTagRepository artistTagRepository;
        private final TagNormalizer tagNormalizer;
    private final TrackProviderStateRepository trackProviderStateRepository;
    private final int ttlDays;
    private final int llmUnknownTtlDays;

    public TagCacheService(
            TrackTagRepository trackTagRepository,
            ArtistTagRepository artistTagRepository,
                        TagNormalizer tagNormalizer,
            TrackProviderStateRepository trackProviderStateRepository,
            @Value("${tagging.cache.ttl-days:30}") int ttlDays,
            @Value("${tagging.llm.unknown-ttl-days:30}") int llmUnknownTtlDays
    ) {
        this.trackTagRepository = trackTagRepository;
        this.artistTagRepository = artistTagRepository;
                this.tagNormalizer = tagNormalizer;
        this.trackProviderStateRepository = trackProviderStateRepository;
        this.ttlDays = ttlDays;
        this.llmUnknownTtlDays = llmUnknownTtlDays;
    }

    // ── Track ─────────────────────────────────────────────────────────────────

    public boolean isTrackTagsCacheValid(String spotifyTrackId, TagSource source) {
        Instant cutoff = Instant.now().minus(ttlDays, ChronoUnit.DAYS);
        return trackTagRepository.findByTrackSpotifyIdAndSource(spotifyTrackId, source).stream()
                .anyMatch(t -> t.getCachedAt().isAfter(cutoff));
    }

    public List<TagResult> getTrackTags(String spotifyTrackId, TagSource source) {
        Instant cutoff = Instant.now().minus(ttlDays, ChronoUnit.DAYS);
        return canonicalize(trackTagRepository.findByTrackSpotifyIdAndSource(spotifyTrackId, source).stream()
                .filter(t -> t.getCachedAt().isAfter(cutoff))
                .map(t -> new TagResult(t.getValue(), t.getType(), t.getSource(), t.getWeight()))
                .toList());
    }

    public Map<String, List<TagResult>> getFreshTrackTagsByTrackIds(List<String> spotifyTrackIds, TagSource source) {
        if (spotifyTrackIds.isEmpty()) {
            return Map.of();
        }

        Instant cutoff = Instant.now().minus(ttlDays, ChronoUnit.DAYS);
        return trackTagRepository.findByTrackSpotifyIdInAndSource(spotifyTrackIds, source).stream()
                .filter(tag -> tag.getCachedAt().isAfter(cutoff))
                .collect(Collectors.groupingBy(
                        TrackTag::getTrackSpotifyId,
                        LinkedHashMap::new,
                        Collectors.collectingAndThen(
                                Collectors.mapping(tag -> new TagResult(tag.getValue(), tag.getType(), tag.getSource(), tag.getWeight()), Collectors.toList()),
                                this::canonicalize
                        )
                ));
    }

    @Transactional
    public void storeTrackTags(String spotifyTrackId, TagSource source, List<TagResult> tags) {
        trackTagRepository.deleteByTrackSpotifyIdAndSource(spotifyTrackId, source);
        Instant now = Instant.now();
        List<TrackTag> entities = canonicalize(tags).stream()
                .map(r -> TrackTag.builder()
                        .trackSpotifyId(spotifyTrackId)
                        .value(r.value())
                        .type(r.type())
                        .source(r.source())
                        .weight(r.weight())
                        .cachedAt(now)
                        .build())
                .toList();
        trackTagRepository.saveAll(entities);
    }

    // ── Artist ────────────────────────────────────────────────────────────────

    public boolean isArtistTagsCacheValid(String spotifyArtistId, TagSource source) {
        Instant cutoff = Instant.now().minus(ttlDays, ChronoUnit.DAYS);
        return artistTagRepository.findByArtistSpotifyIdAndSource(spotifyArtistId, source).stream()
                .anyMatch(t -> t.getCachedAt().isAfter(cutoff));
    }

    public List<TagResult> getArtistTags(String spotifyArtistId, TagSource source) {
        Instant cutoff = Instant.now().minus(ttlDays, ChronoUnit.DAYS);
        return canonicalize(artistTagRepository.findByArtistSpotifyIdAndSource(spotifyArtistId, source).stream()
                .filter(t -> t.getCachedAt().isAfter(cutoff))
                .map(t -> new TagResult(t.getValue(), t.getType(), t.getSource(), t.getWeight()))
                .toList());
    }

    public Map<String, List<TagResult>> getFreshArtistTagsByArtistIds(List<String> spotifyArtistIds, TagSource source) {
        if (spotifyArtistIds.isEmpty()) {
            return Map.of();
        }

        Instant cutoff = Instant.now().minus(ttlDays, ChronoUnit.DAYS);
        return artistTagRepository.findByArtistSpotifyIdInAndSource(spotifyArtistIds, source).stream()
                .filter(tag -> tag.getCachedAt().isAfter(cutoff))
                .collect(Collectors.groupingBy(
                        ArtistTag::getArtistSpotifyId,
                        LinkedHashMap::new,
                        Collectors.collectingAndThen(
                                Collectors.mapping(tag -> new TagResult(tag.getValue(), tag.getType(), tag.getSource(), tag.getWeight()), Collectors.toList()),
                                this::canonicalize
                        )
                ));
    }

    @Transactional
    public void storeArtistTags(String spotifyArtistId, TagSource source, List<TagResult> tags) {
        artistTagRepository.deleteByArtistSpotifyIdAndSource(spotifyArtistId, source);
        Instant now = Instant.now();
        List<ArtistTag> entities = canonicalize(tags).stream()
                .map(r -> ArtistTag.builder()
                        .artistSpotifyId(spotifyArtistId)
                        .value(r.value())
                        .type(r.type())
                        .source(r.source())
                        .weight(r.weight())
                        .cachedAt(now)
                        .build())
                .toList();
        artistTagRepository.saveAll(entities);
    }

    // ── Provider State (negative caching) ────────────────────────────────────

    /**
     * Returns true if a fresh provider-state record exists and its {@code refreshAfter} is still
     * in the future — meaning we should not call the provider again yet.
     */
    public boolean isProviderStateFresh(String trackSpotifyId, TagSource source) {
        return trackProviderStateRepository
                .findByTrackSpotifyIdAndSource(trackSpotifyId, source)
                .map(s -> s.getRefreshAfter().isAfter(Instant.now()))
                .orElse(false);
    }

    /**
     * Returns the current provider-state status for the track, or empty if none exists.
     */
    public java.util.Optional<ProviderLookupStatus> getProviderStatus(String trackSpotifyId, TagSource source) {
        return trackProviderStateRepository
                .findByTrackSpotifyIdAndSource(trackSpotifyId, source)
                .map(TrackProviderState::getStatus);
    }

        public Map<String, TrackProviderState> getProviderStatesByTrackIds(List<String> trackSpotifyIds, TagSource source) {
                if (trackSpotifyIds.isEmpty()) {
                        return Map.of();
                }

                return trackProviderStateRepository.findByTrackSpotifyIdInAndSource(trackSpotifyIds, source).stream()
                                .collect(Collectors.toMap(
                                                TrackProviderState::getTrackSpotifyId,
                                                Function.identity(),
                                                (left, right) -> right,
                                                LinkedHashMap::new
                                ));
        }

    /**
     * Persists or updates the provider-state record for a track.
     * TTL depends on status: HIT uses {@code ttlDays}, others use {@code llmUnknownTtlDays}.
     */
    @Transactional
    public void storeProviderState(String trackSpotifyId, TagSource source, ProviderLookupStatus status,
            String modelName, String matchStrategy, String reasonCode, Double confidence) {
        Instant now = Instant.now();
        int days = (status == ProviderLookupStatus.HIT) ? ttlDays : llmUnknownTtlDays;
        Instant refreshAfter = now.plus(days, ChronoUnit.DAYS);

        TrackProviderState state = trackProviderStateRepository
                .findByTrackSpotifyIdAndSource(trackSpotifyId, source)
                .orElseGet(() -> TrackProviderState.builder()
                        .trackSpotifyId(trackSpotifyId)
                        .source(source)
                        .build());

        state.setStatus(status);
        state.setFetchedAt(now);
        state.setRefreshAfter(refreshAfter);
        state.setModelName(modelName);
        state.setMatchStrategy(matchStrategy);
        state.setReasonCode(reasonCode);
        state.setConfidence(confidence);
        trackProviderStateRepository.save(state);
    }

        private List<TagResult> canonicalize(List<TagResult> tags) {
                Map<String, TagResult> normalizedByKey = new LinkedHashMap<>();
                for (TagResult tag : tags) {
                        tagNormalizer.normalize(tag.value(), tag.source(), tag.weight())
                                        .ifPresent(normalized -> {
                                                String key = normalized.source() + "|" + normalized.type() + "|" + normalized.value();
                                                TagResult existing = normalizedByKey.get(key);
                                                if (existing == null || normalized.weight() > existing.weight()) {
                                                        normalizedByKey.put(key, normalized);
                                                }
                                        });
                }
                return List.copyOf(normalizedByKey.values());
        }
}
