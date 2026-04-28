package dev.sdklab.spotifysort.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.sdklab.spotifysort.engine.SyncSuggestEngine;
import dev.sdklab.spotifysort.engine.TrackTagger;
import dev.sdklab.spotifysort.model.PlaylistSummary;
import dev.sdklab.spotifysort.model.RawArtist;
import dev.sdklab.spotifysort.model.RawTrack;
import dev.sdklab.spotifysort.model.ScanJob;
import dev.sdklab.spotifysort.model.ScanStatus;
import dev.sdklab.spotifysort.model.SyncSuggestResult;
import dev.sdklab.spotifysort.model.TaggedTrack;
import dev.sdklab.spotifysort.repository.ScanJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import se.michaelthelin.spotify.model_objects.specification.AudioFeatures;

@Service
@RequiredArgsConstructor
@Slf4j
public class ScanService {

    private final ScanJobRepository scanJobRepository;
    private final SpotifyClientService spotifyClientService;
    private final TrackTagger trackTagger;
    private final SyncSuggestEngine syncSuggestEngine;
    private final ObjectMapper objectMapper;

    /**
     * Persists a new PENDING scan job and returns its ID.
     * The caller is responsible for triggering {@link #runScan(Long)} afterwards.
     */
    public Long createScan(Long userId, String sourcePlaylistId, int threshold) {
        ScanJob job = ScanJob.builder()
                .userId(userId)
                .sourcePlaylistId(sourcePlaylistId)
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

            // 2. Collect unique artist IDs across all tracks
            Set<String> artistIds = tracks.stream()
                    .flatMap(t -> t.artistIds().stream())
                    .collect(Collectors.toSet());

            // 3. Batch-fetch full Artist objects (needed for genres)
            Map<String, RawArtist> artistMap = spotifyClientService.getArtistsByIds(userId, artistIds);

            // 4. Batch-fetch audio features
            List<String> trackIds = tracks.stream().map(RawTrack::id).collect(Collectors.toList());
            Map<String, AudioFeatures> featuresMap = spotifyClientService.getAudioFeatures(userId, trackIds);

            // 5. Tag every track
            List<TaggedTrack> taggedTracks = new ArrayList<>();
            for (RawTrack track : tracks) {
                List<RawArtist> artists = track.artistIds().stream()
                        .map(artistMap::get)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());

                AudioFeatures features = featuresMap.get(track.id());
                taggedTracks.add(trackTagger.tag(track, artists, features));
            }

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
