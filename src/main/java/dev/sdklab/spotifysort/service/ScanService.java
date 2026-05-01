package dev.sdklab.spotifysort.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

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

    /** In-memory cancel signals per active job; checked on every loop iteration for near-instant interruption. */
    private final ConcurrentHashMap<Long, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();

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
    public Long createScan(Long userId, List<String> sourcePlaylistIds, int threshold) {
        List<String> effectiveSourcePlaylistIds = resolveRequestedSourcePlaylistIds(sourcePlaylistIds);
        String primarySourcePlaylistId = effectiveSourcePlaylistIds.get(0);

        ScanJob job = ScanJob.builder()
                .userId(userId)
                .sourcePlaylistId(primarySourcePlaylistId)
                .sourcePlaylistIdsJson(writeValue(effectiveSourcePlaylistIds))
                .threshold(threshold)
                .status(ScanStatus.PENDING)
                .currentStep("Queued")
                .progressPercent(0)
                .currentItem(0)
                .totalItems(0)
                .currentFetchRequest(0)
                .totalFetchRequests(0)
                .cancelRequested(false)
                .applied(false)
                .undone(false)
                .build();
        return scanJobRepository.save(job).getId();
    }

    public void requestCancel(Long jobId) {
        // Signal the running scan thread immediately via in-memory flag (no DB round-trip needed)
        AtomicBoolean existingFlag = cancelFlags.get(jobId);
        if (existingFlag != null) {
            existingFlag.set(true);
        }
        scanJobRepository.findById(jobId).ifPresent(job -> {
            if (job.getStatus() == ScanStatus.DONE || job.getStatus() == ScanStatus.FAILED || job.getStatus() == ScanStatus.CANCELLED) {
                return;
            }
            job.setCancelRequested(true);
            job.setStatus(ScanStatus.CANCELLING);
            job.setCurrentStep("Cancelling scan\u2026");
            scanJobRepository.save(job);
        });
    }

    /**
     * On startup, finalize any jobs that were left in an active state by a previous server instance.
     * RUNNING/PENDING jobs → FAILED; CANCELLING jobs → CANCELLED.
     */
    @PostConstruct
    public void recoverStuckJobs() {
        List<ScanJob> stuck = scanJobRepository.findByStatusIn(
                List.of(ScanStatus.RUNNING, ScanStatus.CANCELLING, ScanStatus.PENDING));
        for (ScanJob job : stuck) {
            if (job.getStatus() == ScanStatus.CANCELLING) {
                job.setStatus(ScanStatus.CANCELLED);
                job.setCurrentStep("Scan cancelled");
                job.setCancelRequested(true);
            } else {
                job.setStatus(ScanStatus.FAILED);
                job.setCurrentStep("Scan interrupted");
                job.setErrorMessage("Scan was interrupted by a server restart.");
            }
        }
        if (!stuck.isEmpty()) {
            scanJobRepository.saveAll(stuck);
            log.info("Recovered {} stuck scan job(s) on startup", stuck.size());
        }
    }

    /**
     * Executes the full scan pipeline asynchronously.
     * Must be called via the Spring proxy (i.e., from a different bean) for @Async to take effect.
     */
    @Async
    public void runScan(Long jobId) {
        ScanJob job = scanJobRepository.findById(jobId).orElseThrow();
        // Register in-memory cancel flag; initialised from the current DB state so that a cancel
        // request that arrived before this thread started is honoured immediately.
        AtomicBoolean cancelFlag = new AtomicBoolean(job.isCancelRequested());
        cancelFlags.put(jobId, cancelFlag);
        if (cancelFlag.get()) {
            job.setStatus(ScanStatus.CANCELLED);
            job.setCurrentStep("Scan cancelled");
            scanJobRepository.save(job);
            cancelFlags.remove(jobId);
            return;
        }
        job.setStatus(ScanStatus.RUNNING);
        updateProgress(job, 2, "Starting scan…");

        try {
            Long userId = job.getUserId();
            List<String> sourcePlaylistIds = resolveSourcePlaylistIds(job);
            throwIfCancelRequested(jobId);

            // 1. Fetch all tracks from source playlist
            updateProgress(job, 12, "Loading source tracks…");
            List<RawTrack> tracks = loadTracksFromSources(userId, sourcePlaylistIds, jobId, job);
            updateItemProgress(job, 0, tracks.size(), "Loaded source tracks");
            throwIfCancelRequested(jobId);

            // 2. Upsert Track entities
            updateProgress(job, 28, "Saving track and artist metadata…");
            saveMissingTracks(tracks);
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

            saveMissingArtists(artistIdToName);
            throwIfCancelRequested(jobId);

            // 4. Enrich tags via Last.fm (with DB caching)
            updateProgress(job, 50, "Enriching genre and mood tags…");
            int enrichmentUpdateStep = Math.max(1, tracks.size() / 24);
            Map<String, Set<String>> enrichedTags = tagEnrichmentService.enrichTracks(tracks, (current, total) -> {                // Fast cancel check on every track — in-memory, no DB hit
                throwIfCancelRequested(jobId);                if (shouldPersistEnrichmentProgress(current, total, enrichmentUpdateStep)) {
                    updateItemProgress(job, current, total, "Enriching genre and mood tags…");
                }
            });
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
            job.setErrorMessage("Scan failed. Please try again.");
            job.setCurrentStep("Scan failed");
        } finally {
            cancelFlags.remove(jobId);
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
                filteredUpdates.add(new PlaylistUpdate(update.playlistId(), update.playlistName(), update.totalTracks(), missingTracks));
            }
        }

        return new SyncSuggestResult(filteredUpdates, result.newIdeas());
    }

    private void updateProgress(ScanJob job, int percent, String currentStep) {
        job.setProgressPercent(percent);
        job.setCurrentStep(currentStep);
        scanJobRepository.save(job);
    }

    private void updateItemProgress(ScanJob job, int currentItem, int totalItems, String currentStep) {
        job.setCurrentItem(currentItem);
        job.setTotalItems(totalItems);
        job.setCurrentStep(currentStep);
        scanJobRepository.save(job);
    }

    private void updateFetchProgress(ScanJob job, int currentFetchRequest, int totalFetchRequests) {
        job.setCurrentFetchRequest(currentFetchRequest);
        job.setTotalFetchRequests(totalFetchRequests);
        scanJobRepository.save(job);
    }

    private List<RawTrack> loadTracksFromSources(Long userId, List<String> sourcePlaylistIds, Long jobId, ScanJob job) throws Exception {
        Map<String, RawTrack> deduplicatedTracks = new LinkedHashMap<>();
        int completedFetchRequests = 0;

        for (String sourcePlaylistId : sourcePlaylistIds) {
            throwIfCancelRequested(jobId);

            final int requestOffset = completedFetchRequests;
            final int[] sourceTotalRequests = {0};

            List<RawTrack> sourceTracks = spotifyClientService.getPlaylistTracks(userId, sourcePlaylistId,
                    (currentRequest, totalRequests) -> {
                        // Cancel check on every Spotify page — in-memory, no DB hit
                        throwIfCancelRequested(jobId);
                        sourceTotalRequests[0] = Math.max(sourceTotalRequests[0], totalRequests);
                        updateFetchProgress(
                                job,
                                requestOffset + currentRequest,
                                Math.max(requestOffset + totalRequests, requestOffset + currentRequest)
                        );
                    });

            for (RawTrack track : sourceTracks) {
                deduplicatedTracks.putIfAbsent(track.id(), track);
            }

            completedFetchRequests += Math.max(sourceTotalRequests[0], 1);
            updateFetchProgress(job, completedFetchRequests, completedFetchRequests);
        }

        return new ArrayList<>(deduplicatedTracks.values());
    }

    private boolean shouldPersistEnrichmentProgress(int currentItem, int totalItems, int updateStep) {
        return currentItem <= 1
                || currentItem >= totalItems
                || currentItem % updateStep == 0;
    }

    private List<String> resolveRequestedSourcePlaylistIds(List<String> sourcePlaylistIds) {
        if (sourcePlaylistIds == null || sourcePlaylistIds.isEmpty()) {
            return List.of(SpotifyClientService.LIKED_SONGS_SOURCE_ID);
        }

        List<String> normalized = sourcePlaylistIds.stream()
                .filter(sourceId -> sourceId != null && !sourceId.isBlank())
                .distinct()
                .toList();

        return normalized.isEmpty() ? List.of(SpotifyClientService.LIKED_SONGS_SOURCE_ID) : normalized;
    }

    public List<String> resolveSourcePlaylistIds(ScanJob job) {
        String sourcePlaylistIdsJson = job.getSourcePlaylistIdsJson();
        if (sourcePlaylistIdsJson == null || sourcePlaylistIdsJson.isBlank()) {
            return List.of(job.getSourcePlaylistId());
        }

        try {
            return resolveRequestedSourcePlaylistIds(objectMapper.readValue(
                    sourcePlaylistIdsJson,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)
            ));
        } catch (Exception e) {
            log.warn("Failed to deserialize source playlist ids for job {}", job.getId(), e);
            return List.of(job.getSourcePlaylistId());
        }
    }

    private String writeValue(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize scan job payload", e);
        }
    }

    private void saveMissingTracks(List<RawTrack> tracks) {
        Set<String> trackIds = tracks.stream()
            .map(RawTrack::id)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> existingIds = trackRepository.findAllBySpotifyIdIn(trackIds).stream()
            .map(Track::getSpotifyId)
            .collect(Collectors.toSet());

        List<Track> missingTracks = tracks.stream()
            .filter(raw -> !existingIds.contains(raw.id()))
            .collect(Collectors.toMap(
                RawTrack::id,
                raw -> Track.builder()
                    .spotifyId(raw.id())
                    .name(raw.name())
                    .uri(raw.uri())
                    .build(),
                (left, right) -> left,
                java.util.LinkedHashMap::new
            ))
            .values()
            .stream()
            .toList();

        if (!missingTracks.isEmpty()) {
            trackRepository.saveAll(missingTracks);
        }
    }

    private void saveMissingArtists(Map<String, String> artistIdToName) {
        Set<String> artistIds = artistIdToName.keySet();
        Set<String> existingIds = artistRepository.findAllBySpotifyIdIn(artistIds).stream()
            .map(Artist::getSpotifyId)
            .collect(Collectors.toSet());

        List<Artist> missingArtists = artistIdToName.entrySet().stream()
            .filter(entry -> !existingIds.contains(entry.getKey()))
            .map(entry -> Artist.builder()
                .spotifyId(entry.getKey())
                .name(entry.getValue())
                .build())
            .toList();

        if (!missingArtists.isEmpty()) {
            artistRepository.saveAll(missingArtists);
        }
    }

    private void throwIfCancelRequested(Long jobId) {
        AtomicBoolean flag = cancelFlags.get(jobId);
        if (flag != null) {
            // Fast path: in-memory check — no DB round-trip
            if (flag.get()) throw new ScanCancelledException();
            return;
        }
        // Fallback: DB check (no in-memory flag means the job is not actively running here,
        // e.g. a stale call after server restart)
        ScanJob freshJob = scanJobRepository.findById(jobId).orElseThrow();
        if (freshJob.isCancelRequested()) {
            throw new ScanCancelledException();
        }
    }

    private static final class ScanCancelledException extends RuntimeException {
    }
}
