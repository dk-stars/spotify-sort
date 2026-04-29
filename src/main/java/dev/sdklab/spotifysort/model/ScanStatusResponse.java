package dev.sdklab.spotifysort.model;

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
	int totalFetchRequests
) {}
