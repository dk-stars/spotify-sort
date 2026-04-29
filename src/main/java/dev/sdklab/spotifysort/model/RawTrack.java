package dev.sdklab.spotifysort.model;

import java.util.List;

/**
 * Minimal track data extracted from the Spotify /playlists/{id}/items endpoint.
 * Replaces the library's Track object to avoid the deprecated /tracks endpoint.
 */
public record RawTrack(
	String id,
	String name,
	String uri,
	List<String> artistIds,
	List<String> artistNames,
	String albumImageUrl
) {}
