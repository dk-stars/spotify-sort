package dev.sdklab.spotifysort.model;

import java.util.List;

public record TrackRef(
	String trackId,
	String trackName,
	String trackUri,
	List<String> artistNames,
	String albumImageUrl
) {}
