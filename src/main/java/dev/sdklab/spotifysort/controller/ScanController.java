package dev.sdklab.spotifysort.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;

import com.fasterxml.jackson.databind.ObjectMapper;

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
            @SessionAttribute(name = "userId", required = false) Long userId,
            @RequestBody ScanRequest request) {

        if (userId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        Long jobId = scanService.createScan(userId, request.sourcePlaylistId(), request.threshold());
        scanService.runScan(jobId);  // @Async — returns immediately

        return ResponseEntity.accepted().body(Map.of("jobId", jobId));
    }

    @PostMapping("/{jobId}/cancel")
    public ResponseEntity<?> cancelScan(
            @SessionAttribute(name = "userId", required = false) Long userId,
            @PathVariable Long jobId) {

        if (userId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        ScanJob job = scanJobRepository.findById(jobId).orElse(null);
        if (job == null || !job.getUserId().equals(userId)) {
            return ResponseEntity.notFound().build();
        }

        scanService.requestCancel(jobId);
        return ResponseEntity.accepted().body(Map.of("jobId", jobId, "status", "CANCELLING"));
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<?> getStatus(
            @SessionAttribute(name = "userId", required = false) Long userId,
            @PathVariable Long jobId) {

        if (userId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        ScanJob job = scanJobRepository.findById(jobId).orElse(null);
        if (job == null || !job.getUserId().equals(userId)) {
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
            job.getTotalFetchRequests()
        ));
    }
}
