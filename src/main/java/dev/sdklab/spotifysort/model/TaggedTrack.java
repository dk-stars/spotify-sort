package dev.sdklab.spotifysort.model;

import java.util.List;
import java.util.Set;

public record TaggedTrack(
	String trackId,
	String trackName,
	String trackUri,
	List<String> artistNames,
	String albumImageUrl,
	Set<String> tags
) {}
