package dev.sdklab.spotifysort.tagging.api;

import java.util.List;

import dev.sdklab.spotifysort.model.RawTrack;

/**
 * Batch tag provider backed by an LLM.
 * Unlike {@link TagProvider}, this interface works in track batches to amortise
 * per-request latency and cost over multiple tracks.
 */
public interface LlmTagProvider {

    /**
     * Returns an {@link LlmTrackResult} for each supplied track.
     * Every input track will have a corresponding entry (never null, never missing).
     * Tracks that cannot be identified have status UNKNOWN or AMBIGUOUS with empty tag lists.
     *
     * @param tracks non-null, non-empty list; caller deduplicates by track id before calling
     */
    List<LlmTrackResult> getTagsForTracks(List<RawTrack> tracks);
}
