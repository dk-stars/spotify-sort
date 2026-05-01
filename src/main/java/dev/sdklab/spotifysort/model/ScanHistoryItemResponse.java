package dev.sdklab.spotifysort.model;

import java.time.Instant;
import java.util.List;

public record ScanHistoryItemResponse(
        Long jobId,
        ScanStatus status,
        String currentStep,
        int progressPercent,
        int currentItem,
        int totalItems,
        Instant createdAt,
        List<String> sourcePlaylistIds,
        int threshold,
        boolean hasResult,
        boolean applied,
        boolean undone,
        boolean canUndo
) {}