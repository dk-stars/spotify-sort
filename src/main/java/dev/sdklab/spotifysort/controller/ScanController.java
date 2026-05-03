package dev.sdklab.spotifysort.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.sdklab.spotifysort.model.ExecuteRequest;
import dev.sdklab.spotifysort.model.ExecuteSummary;
import dev.sdklab.spotifysort.model.ScanHistoryItemResponse;
import dev.sdklab.spotifysort.model.ScanJob;
import dev.sdklab.spotifysort.model.ScanRequest;
import dev.sdklab.spotifysort.model.ScanStatus;
import dev.sdklab.spotifysort.model.ScanStatusResponse;
import dev.sdklab.spotifysort.model.SyncSuggestResult;
import dev.sdklab.spotifysort.repository.ScanJobRepository;
import dev.sdklab.spotifysort.service.ScanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/scan")
@RequiredArgsConstructor
@Slf4j
public class ScanController {

    private final ScanService scanService;
    private final ScanJobRepository scanJobRepository;
    private final ObjectMapper objectMapper;

    @PostMapping
    public ResponseEntity<?> startScan(
            @RequestAttribute(name = "userId", required = false) Long userId,
            @RequestBody ScanRequest request) {

        if (userId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        Long jobId = scanService.createScan(userId, request.sourcePlaylistIds(), request.threshold(),
                request.providerMode() != null ? request.providerMode() : dev.sdklab.spotifysort.tagging.api.ProviderMode.LASTFM_ONLY);
        scanService.runScan(jobId);  // @Async — returns immediately

        return ResponseEntity.accepted().body(Map.of("jobId", jobId));
    }

    @GetMapping("/history")
    public ResponseEntity<?> history(@RequestAttribute(name = "userId", required = false) Long userId) {
        if (userId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        List<ScanHistoryItemResponse> history = scanJobRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toHistoryItem)
                .toList();

        return ResponseEntity.ok(history);
    }

    @PostMapping("/{jobId:[0-9]+}/cancel")
    public ResponseEntity<?> cancelScan(
            @RequestAttribute(name = "userId", required = false) Long userId,
            @PathVariable Long jobId) {

        if (userId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        ScanJob job = scanJobRepository.findByIdAndUserId(jobId, userId).orElse(null);
        if (job == null || !job.getUserId().equals(userId)) {
            return ResponseEntity.notFound().build();
        }

        scanService.requestCancel(jobId);
        return ResponseEntity.accepted().body(Map.of("jobId", jobId, "status", "CANCELLING"));
    }

    @GetMapping("/{jobId:[0-9]+}")
    public ResponseEntity<?> getStatus(
            @RequestAttribute(name = "userId", required = false) Long userId,
            @PathVariable Long jobId) {

        if (userId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        ScanJob job = scanJobRepository.findByIdAndUserId(jobId, userId).orElse(null);
        if (job == null) {
            return ResponseEntity.notFound().build();
        }

        SyncSuggestResult result = null;
        if (job.getStatus() == ScanStatus.DONE && job.getResultJson() != null) {
            try {
                result = objectMapper.readValue(job.getResultJson(), SyncSuggestResult.class);
            } catch (Exception e) {
                log.error("Failed to deserialize result for job {}", jobId, e);
            }
        }

        List<String> sourcePlaylistIds = scanService.resolveSourcePlaylistIds(job);
        ExecuteRequest executionRequest = readValue(job.getExecutionRequestJson(), ExecuteRequest.class);
        ExecuteSummary executionSummary = readValue(job.getExecutionSummaryJson(), ExecuteSummary.class);

        return ResponseEntity.ok(new ScanStatusResponse(
            jobId,
            job.getStatus(),
            result,
            job.getErrorMessage(),
            job.getCurrentStep(),
            job.getProgressPercent(),
            job.getCurrentItem(),
            job.getTotalItems(),
            job.getCurrentFetchRequest(),
            job.getTotalFetchRequests(),
            job.getCreatedAt(),
            sourcePlaylistIds,
            job.getThreshold(),
            job.isApplied(),
            job.isUndone(),
            canUndo(job, executionRequest),
            executionRequest,
            executionSummary,
            job.getProviderMode()
        ));
    }

    private ScanHistoryItemResponse toHistoryItem(ScanJob job) {
        ExecuteRequest executionRequest = readValue(job.getExecutionRequestJson(), ExecuteRequest.class);
        return new ScanHistoryItemResponse(
                job.getId(),
                job.getStatus(),
                job.getCurrentStep(),
                job.getProgressPercent(),
                job.getCurrentItem(),
                job.getTotalItems(),
                job.getCreatedAt(),
                scanService.resolveSourcePlaylistIds(job),
                job.getThreshold(),
                job.getResultJson() != null,
                job.isApplied(),
                job.isUndone(),
                canUndo(job, executionRequest),
                job.getProviderMode()
        );
    }

    private boolean canUndo(ScanJob job, ExecuteRequest executionRequest) {
        if (!job.isApplied() || job.isUndone() || executionRequest == null) {
            return false;
        }
        return !executionRequest.updates().isEmpty() || !executionRequest.creates().isEmpty();
    }

    private <T> T readValue(String raw, Class<T> type) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, type);
        } catch (Exception e) {
            log.error("Failed to deserialize {}", type.getSimpleName(), e);
            return null;
        }
    }
}
