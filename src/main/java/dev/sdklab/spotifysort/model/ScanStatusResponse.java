package dev.sdklab.spotifysort.model;

import java.time.Instant;
import java.util.List;

public record ScanStatusResponse(
	Long jobId,
	ScanStatus status,
	SyncSuggestResult result,
	String error,
	String currentStep,
	int progressPercent,
	int currentItem,
	int totalItems,
	int currentFetchRequest,
	int totalFetchRequests,
	Instant createdAt,
	List<String> sourcePlaylistIds,
	int threshold,
	boolean applied,
	boolean undone,
	boolean canUndo,
	ExecuteRequest executionRequest,
	ExecuteSummary executionSummary
) {}
