package dev.sdklab.spotifysort.model;

import java.util.List;

/** The full output of a Sync & Suggest analysis run. Serialized as JSON into ScanJob.resultJson. */
public record SyncSuggestResult(
        List<PlaylistUpdate> playlistsToUpdate,
        List<PlaylistIdea> newIdeas
) {}
