package dev.sdklab.spotifysort.tagging.api;

/**
 * Selects which tag provider(s) are used during a scan.
 *
 * <ul>
 *   <li>{@code LASTFM_ONLY} – use only Last.fm (default, cheapest)</li>
 *   <li>{@code LLM_ONLY}    – use only the LLM provider (evaluation only)</li>
 *   <li>{@code BOTH}        – Last.fm first; LLM as fallback for misses</li>
 * </ul>
 */
public enum ProviderMode {
    LASTFM_ONLY,
    LLM_ONLY,
    BOTH
}
