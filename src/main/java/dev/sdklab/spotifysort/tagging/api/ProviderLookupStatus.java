package dev.sdklab.spotifysort.tagging.api;

/**
 * Represents the outcome of the most recent provider lookup for a track or artist.
 * Used in the provider-state negative cache to avoid re-billing the LLM for tracks
 * that were already returned as unknown or ambiguous.
 */
public enum ProviderLookupStatus {

    /** Provider returned at least one tag — positive hit. */
    HIT,

    /** Provider had no information about this track (e.g. LLM returned UNKNOWN). */
    UNKNOWN,

    /** Provider could not disambiguate the track (e.g. LLM returned AMBIGUOUS). */
    AMBIGUOUS,

    /** Provider explicitly refused to tag this track. */
    REFUSED,

    /** Provider call failed with a transient error (HTTP 5xx, timeout, parse failure). */
    ERROR
}
