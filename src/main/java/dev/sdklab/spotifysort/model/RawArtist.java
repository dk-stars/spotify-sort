package dev.sdklab.spotifysort.model;

import java.util.List;

/**
 * Minimal artist data extracted from the Spotify /artists endpoint.
 * Replaces the library's Artist object to avoid deprecated OAuth 2.0 restricted endpoints.
 */
public record RawArtist(String id, String name, List<String> genres) {}
