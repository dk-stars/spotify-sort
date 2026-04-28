package dev.sdklab.spotifysort.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sdklab.spotifysort.engine.SyncSuggestEngine;
import dev.sdklab.spotifysort.engine.TrackTagger;
import dev.sdklab.spotifysort.model.*;
import dev.sdklab.spotifysort.repository.ScanJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import se.michaelthelin.spotify.model_objects.specification.Artist;
import se.michaelthelin.spotify.model_objects.specification.ArtistSimplified;
import se.michaelthelin.spotify.model_objects.specification.AudioFeatures;
import se.michaelthelin.spotify.model_objects.specification.Track;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

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
            List<Track> tracks = spotifyClientService.getPlaylistTracks(userId, job.getSourcePlaylistId());

            // 2. Collect unique artist IDs across all tracks
            Set<String> artistIds = tracks.stream()
                    .filter(t -> t.getArtists() != null)
                    .flatMap(t -> Arrays.stream(t.getArtists()).map(ArtistSimplified::getId))
                    .collect(Collectors.toSet());

            // 3. Batch-fetch full Artist objects (needed for genres)
            Map<String, Artist> artistMap = spotifyClientService.getArtistsByIds(userId, artistIds);

            // 4. Batch-fetch audio features
            List<String> trackIds = tracks.stream().map(Track::getId).collect(Collectors.toList());
            Map<String, AudioFeatures> featuresMap = spotifyClientService.getAudioFeatures(userId, trackIds);

            // 5. Tag every track
            List<TaggedTrack> taggedTracks = new ArrayList<>();
            for (Track track : tracks) {
                List<Artist> artists = track.getArtists() == null
                        ? Collections.emptyList()
                        : Arrays.stream(track.getArtists())
                                .map(a -> artistMap.get(a.getId()))
                                .filter(Objects::nonNull)
                                .collect(Collectors.toList());

                AudioFeatures features = featuresMap.get(track.getId());
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
            log.error("Scan job {} failed", jobId, e);
            job.setStatus(ScanStatus.FAILED);
            job.setErrorMessage(e.getMessage());
        } finally {
            scanJobRepository.save(job);
        }
    }
}
