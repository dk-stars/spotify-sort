package dev.sdklab.spotifysort.tagging.api;

import java.util.List;

public interface TagProvider {

    /**
     * Returns genre and mood tags for a specific track.
     * Returns an empty list (never null) when the provider has no data.
     */
    List<TagResult> getTagsForTrack(String artistName, String trackName);

    /**
     * Returns genre and mood tags for an artist.
     * Used as a fallback when track-level lookup returns empty.
     * Returns an empty list (never null) when the provider has no data.
     */
    List<TagResult> getTagsForArtist(String artistName);
}
