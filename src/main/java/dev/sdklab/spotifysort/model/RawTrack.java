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
	String albumImageUrl,
	String albumName,
	String releaseDate,
	String releaseDatePrecision,
	String isrc,
	long durationMs,
	boolean explicit
) {
	/** Backwards-compatible constructor for existing call-sites that don't supply the new fields. */
	public RawTrack(String id, String name, String uri, List<String> artistIds, List<String> artistNames, String albumImageUrl) {
		this(id, name, uri, artistIds, artistNames, albumImageUrl, null, null, null, null, 0, false);
	}
}
