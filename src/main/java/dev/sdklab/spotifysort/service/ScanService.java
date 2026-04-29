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
import dev.sdklab.spotifysort.model.PlaylistUpdate;
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
                .currentStep("Queued")
                .progressPercent(0)
                .cancelRequested(false)
                .build();
        return scanJobRepository.save(job).getId();
    }

    public void requestCancel(Long jobId) {
        scanJobRepository.findById(jobId).ifPresent(job -> {
            if (job.getStatus() == ScanStatus.DONE || job.getStatus() == ScanStatus.FAILED || job.getStatus() == ScanStatus.CANCELLED) {
                return;
            }
            job.setCancelRequested(true);
            job.setCurrentStep("Cancelling scan…");
            scanJobRepository.save(job);
        });
    }

    /**
     * Executes the full scan pipeline asynchronously.
     * Must be called via the Spring proxy (i.e., from a different bean) for @Async to take effect.
     */
    @Async
    public void runScan(Long jobId) {
        ScanJob job = scanJobRepository.findById(jobId).orElseThrow();
        job.setStatus(ScanStatus.RUNNING);
        updateProgress(job, 2, "Starting scan…");

        try {
            Long userId = job.getUserId();
            throwIfCancelRequested(jobId);

            // 1. Fetch all tracks from source playlist
            updateProgress(job, 12, "Loading source tracks…");
            List<RawTrack> tracks = spotifyClientService.getPlaylistTracks(userId, job.getSourcePlaylistId());
            throwIfCancelRequested(jobId);

            // 2. Upsert Track entities
            updateProgress(job, 28, "Saving track and artist metadata…");
            for (RawTrack raw : tracks) {
                if (!trackRepository.existsBySpotifyId(raw.id())) {
                    trackRepository.save(Track.builder()
                            .spotifyId(raw.id())
                            .name(raw.name())
                            .uri(raw.uri())
                            .build());
                }
            }
            throwIfCancelRequested(jobId);

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
            throwIfCancelRequested(jobId);

            // 4. Enrich tags via Last.fm (with DB caching)
            updateProgress(job, 50, "Enriching genre and mood tags…");
            Map<String, Set<String>> enrichedTags = tagEnrichmentService.enrichTracks(tracks);
            throwIfCancelRequested(jobId);

            // 5. Tag every track
            updateProgress(job, 64, "Building tag groups…");
            List<TaggedTrack> taggedTracks = tracks.stream()
                    .map(t -> trackTagger.tag(t, enrichedTags))
                    .collect(Collectors.toList());
            throwIfCancelRequested(jobId);

            // 6. Fetch user's existing playlists for matching
            updateProgress(job, 76, "Loading existing playlists…");
            List<PlaylistSummary> playlists = spotifyClientService.getUserPlaylists(userId);
            throwIfCancelRequested(jobId);

            // 7. Run Sync & Suggest
            updateProgress(job, 88, "Filtering existing playlist matches…");
            SyncSuggestResult result = syncSuggestEngine.analyze(taggedTracks, playlists, job.getThreshold());
            result = filterAlreadyIncludedTracks(userId, result, jobId);
            throwIfCancelRequested(jobId);

            // 8. Persist result
            updateProgress(job, 96, "Finalizing proposal…");
            job.setResultJson(objectMapper.writeValueAsString(result));
            job.setStatus(ScanStatus.DONE);
            job.setCurrentStep("Scan complete");
            job.setProgressPercent(100);

        } catch (ScanCancelledException e) {
            log.info("Scan job {} cancelled", jobId);
            job.setStatus(ScanStatus.CANCELLED);
            job.setCurrentStep("Scan cancelled");
            job.setErrorMessage(null);
        } catch (Exception e) {
            log.error("Scan job {} failed: {}", jobId, e.getMessage(), e);
            job.setStatus(ScanStatus.FAILED);
            job.setErrorMessage(e.getMessage());
            job.setCurrentStep("Scan failed");
        } finally {
            scanJobRepository.save(job);
        }
    }

    private SyncSuggestResult filterAlreadyIncludedTracks(Long userId, SyncSuggestResult result, Long jobId) throws Exception {
        List<PlaylistUpdate> filteredUpdates = new ArrayList<>();
        for (PlaylistUpdate update : result.playlistsToUpdate()) {
            throwIfCancelRequested(jobId);
            Set<String> existingTrackUris = spotifyClientService.getPlaylistTrackUris(userId, update.playlistId());
            List<dev.sdklab.spotifysort.model.TrackRef> missingTracks = update.tracks().stream()
                    .filter(track -> !existingTrackUris.contains(track.trackUri()))
                    .toList();
            if (!missingTracks.isEmpty()) {
                filteredUpdates.add(new PlaylistUpdate(update.playlistId(), update.playlistName(), missingTracks));
            }
        }

        return new SyncSuggestResult(filteredUpdates, result.newIdeas());
    }

    private void updateProgress(ScanJob job, int percent, String currentStep) {
        job.setProgressPercent(percent);
        job.setCurrentStep(currentStep);
        scanJobRepository.save(job);
    }

    private void throwIfCancelRequested(Long jobId) {
        ScanJob freshJob = scanJobRepository.findById(jobId).orElseThrow();
        if (freshJob.isCancelRequested()) {
            throw new ScanCancelledException();
        }
    }

    private static final class ScanCancelledException extends RuntimeException {
    }
}
