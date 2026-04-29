package dev.sdklab.spotifysort.tagging.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.sdklab.spotifysort.model.ArtistTag;
import dev.sdklab.spotifysort.model.TrackTag;
import dev.sdklab.spotifysort.repository.ArtistTagRepository;
import dev.sdklab.spotifysort.repository.TrackTagRepository;
import dev.sdklab.spotifysort.tagging.api.TagResult;
import dev.sdklab.spotifysort.tagging.api.TagSource;
import dev.sdklab.spotifysort.tagging.norm.TagNormalizer;

@Service
public class TagCacheService {

    private final TrackTagRepository trackTagRepository;
    private final ArtistTagRepository artistTagRepository;
        private final TagNormalizer tagNormalizer;
    private final int ttlDays;

    public TagCacheService(
            TrackTagRepository trackTagRepository,
            ArtistTagRepository artistTagRepository,
                        TagNormalizer tagNormalizer,
            @Value("${tagging.cache.ttl-days:30}") int ttlDays
    ) {
        this.trackTagRepository = trackTagRepository;
        this.artistTagRepository = artistTagRepository;
                this.tagNormalizer = tagNormalizer;
        this.ttlDays = ttlDays;
    }

    // ── Track ─────────────────────────────────────────────────────────────────

    public boolean isTrackTagsCacheValid(String spotifyTrackId, TagSource source) {
        Instant cutoff = Instant.now().minus(ttlDays, ChronoUnit.DAYS);
        return trackTagRepository.findByTrackSpotifyId(spotifyTrackId).stream()
                .filter(t -> t.getSource() == source)
                .anyMatch(t -> t.getCachedAt().isAfter(cutoff));
    }

    public List<TagResult> getTrackTags(String spotifyTrackId) {
        Instant cutoff = Instant.now().minus(ttlDays, ChronoUnit.DAYS);
        return canonicalize(trackTagRepository.findByTrackSpotifyId(spotifyTrackId).stream()
                .filter(t -> t.getCachedAt().isAfter(cutoff))
                .map(t -> new TagResult(t.getValue(), t.getType(), t.getSource(), t.getWeight()))
                .toList());
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
        return artistTagRepository.findByArtistSpotifyId(spotifyArtistId).stream()
                .filter(t -> t.getSource() == source)
                .anyMatch(t -> t.getCachedAt().isAfter(cutoff));
    }

    public List<TagResult> getArtistTags(String spotifyArtistId) {
        Instant cutoff = Instant.now().minus(ttlDays, ChronoUnit.DAYS);
        return canonicalize(artistTagRepository.findByArtistSpotifyId(spotifyArtistId).stream()
                .filter(t -> t.getCachedAt().isAfter(cutoff))
                .map(t -> new TagResult(t.getValue(), t.getType(), t.getSource(), t.getWeight()))
                .toList());
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
