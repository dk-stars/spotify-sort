package dev.sdklab.spotifysort.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.sdklab.spotifysort.engine.SyncSuggestEngine;
import dev.sdklab.spotifysort.engine.TrackTagger;
import dev.sdklab.spotifysort.model.Artist;
import dev.sdklab.spotifysort.model.PlaylistSummary;
import dev.sdklab.spotifysort.model.RawTrack;
import dev.sdklab.spotifysort.model.ScanJob;
import dev.sdklab.spotifysort.model.ScanStatus;
import dev.sdklab.spotifysort.model.SyncSuggestResult;
import dev.sdklab.spotifysort.model.TaggedTrack;
import dev.sdklab.spotifysort.model.Track;
import dev.sdklab.spotifysort.repository.ArtistRepository;
import dev.sdklab.spotifysort.repository.ScanJobRepository;
import dev.sdklab.spotifysort.repository.TrackRepository;
import dev.sdklab.spotifysort.tagging.service.TagEnrichmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class ScanService {

    private final ScanJobRepository scanJobRepository;
    private final SpotifyClientService spotifyClientService;
    private final TrackRepository trackRepository;
    private final ArtistRepository artistRepository;
    private final TagEnrichmentService tagEnrichmentService;
    private final TrackTagger trackTagger;
    private final SyncSuggestEngine syncSuggestEngine;
    private final ObjectMapper objectMapper;

    /**
     * Persists a new PENDING scan job and returns its ID.
     * The caller is responsible for triggering {@link #runScan(Long)} afterwards.
     */
    public Long createScan(Long userId, String sourcePlaylistId, int threshold) {
        String effectiveSourcePlaylistId = (sourcePlaylistId == null || sourcePlaylistId.isBlank())
            ? SpotifyClientService.LIKED_SONGS_SOURCE_ID
            : sourcePlaylistId;

        ScanJob job = ScanJob.builder()
                .userId(userId)
            .sourcePlaylistId(effectiveSourcePlaylistId)
                .threshold(threshold)
                .status(ScanStatus.PENDING)
                .build();
        return scanJobRepository.save(job).getId();
    }

    /**
     * Executes the full scan pipeline asynchronously.
     * Must be called via the Spring proxy (i.e., from a different bean) for @Async to take effect.
     */
    @Async
    public void runScan(Long jobId) {
        ScanJob job = scanJobRepository.findById(jobId).orElseThrow();
        job.setStatus(ScanStatus.RUNNING);
        scanJobRepository.save(job);

        try {
            Long userId = job.getUserId();

            // 1. Fetch all tracks from source playlist
            List<RawTrack> tracks = spotifyClientService.getPlaylistTracks(userId, job.getSourcePlaylistId());

            // 2. Upsert Track entities
            for (RawTrack raw : tracks) {
                if (!trackRepository.existsBySpotifyId(raw.id())) {
                    trackRepository.save(Track.builder()
                            .spotifyId(raw.id())
                            .name(raw.name())
                            .uri(raw.uri())
                            .build());
                }
            }

            // 3. Upsert Artist entities (deduplicate across tracks)
            Map<String, String> artistIdToName = tracks.stream()
                    .flatMap(t -> {
                        List<String> ids = t.artistIds();
                        List<String> names = t.artistNames();
                        List<Map.Entry<String, String>> pairs = new ArrayList<>();
                        for (int i = 0; i < ids.size() && i < names.size(); i++) {
                            pairs.add(Map.entry(ids.get(i), names.get(i)));
                        }
                        return pairs.stream();
                    })
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));

            for (Map.Entry<String, String> entry : artistIdToName.entrySet()) {
                if (!artistRepository.existsBySpotifyId(entry.getKey())) {
                    artistRepository.save(Artist.builder()
                            .spotifyId(entry.getKey())
                            .name(entry.getValue())
                            .build());
                }
            }

            // 4. Enrich tags via Last.fm (with DB caching)
            Map<String, Set<String>> enrichedTags = tagEnrichmentService.enrichTracks(tracks);

            // 5. Tag every track
            List<TaggedTrack> taggedTracks = tracks.stream()
                    .map(t -> trackTagger.tag(t, enrichedTags))
                    .collect(Collectors.toList());

            // 6. Fetch user's existing playlists for matching
            List<PlaylistSummary> playlists = spotifyClientService.getUserPlaylists(userId);

            // 7. Run Sync & Suggest
            SyncSuggestResult result = syncSuggestEngine.analyze(taggedTracks, playlists, job.getThreshold());

            // 8. Persist result
            job.setResultJson(objectMapper.writeValueAsString(result));
            job.setStatus(ScanStatus.DONE);

        } catch (Exception e) {
            log.error("Scan job {} failed: {}", jobId, e.getMessage(), e);
            job.setStatus(ScanStatus.FAILED);
            job.setErrorMessage(e.getMessage());
        } finally {
            scanJobRepository.save(job);
        }
    }
}
