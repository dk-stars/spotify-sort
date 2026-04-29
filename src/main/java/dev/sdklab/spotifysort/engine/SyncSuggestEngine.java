package dev.sdklab.spotifysort.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import dev.sdklab.spotifysort.model.PlaylistIdea;
import dev.sdklab.spotifysort.model.PlaylistSummary;
import dev.sdklab.spotifysort.model.PlaylistUpdate;
import dev.sdklab.spotifysort.model.SyncSuggestResult;
import dev.sdklab.spotifysort.model.TaggedTrack;
import dev.sdklab.spotifysort.model.TrackRef;

/**
 * Core "Sync & Suggest" algorithm.
 *
 * <p>For each tag found across all tagged tracks it applies one of three conditions:
 * <ul>
 *   <li>A — tag matches an existing playlist name → queue tracks for that playlist</li>
 *   <li>B — no match but track count >= threshold → propose creating a new playlist</li>
 *   <li>C — no match and below threshold → silently ignored</li>
 * </ul>
 */
@Component
public class SyncSuggestEngine {

    public SyncSuggestResult analyze(
            List<TaggedTrack> taggedTracks,
            List<PlaylistSummary> existingPlaylists,
            int threshold) {

        // Build a lowercase name → playlist map for case-insensitive matching
        Map<String, PlaylistSummary> playlistByName = existingPlaylists.stream()
                .collect(Collectors.toMap(
                        p -> p.name().toLowerCase(),
                        p -> p,
                        (first, second) -> first   // keep first on duplicate names
                ));

        // Group tracks by tag
        Map<String, List<TaggedTrack>> tracksByTag = new LinkedHashMap<>();
        for (TaggedTrack track : taggedTracks) {
            for (String tag : track.tags()) {
                tracksByTag.computeIfAbsent(tag, k -> new ArrayList<>()).add(track);
            }
        }

        List<PlaylistUpdate> playlistsToUpdate = new ArrayList<>();
        List<PlaylistIdea> newIdeas = new ArrayList<>();

        for (Map.Entry<String, List<TaggedTrack>> entry : tracksByTag.entrySet()) {
            String tag = entry.getKey();
            List<TaggedTrack> tracks = entry.getValue();

            PlaylistSummary existing = playlistByName.get(tag.toLowerCase());

            if (existing != null) {
                // Condition A: existing playlist found
                playlistsToUpdate.add(new PlaylistUpdate(existing.id(), existing.name(), existing.totalTracks(), toRefs(tracks)));
            } else if (tracks.size() >= threshold) {
                // Condition B: enough tracks to suggest a new playlist
                newIdeas.add(new PlaylistIdea(tag, toRefs(tracks)));
            }
            // Condition C: below threshold, no existing playlist — silently ignored
        }

        return new SyncSuggestResult(playlistsToUpdate, newIdeas);
    }

    private List<TrackRef> toRefs(List<TaggedTrack> tracks) {
        return tracks.stream()
            .map(t -> new TrackRef(
                t.trackId(),
                t.trackName(),
                t.trackUri(),
                t.artistNames(),
                t.albumImageUrl()
            ))
                .collect(Collectors.toList());
    }
}
