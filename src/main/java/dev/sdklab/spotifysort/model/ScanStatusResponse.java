package dev.sdklab.spotifysort.model;

public record ScanStatusResponse(
	Long jobId,
	ScanStatus status,
	SyncSuggestResult result,
	String error,
	String currentStep,
	int progressPercent
) {}
