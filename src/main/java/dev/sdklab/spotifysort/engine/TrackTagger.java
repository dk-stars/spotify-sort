package dev.sdklab.spotifysort.engine;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import dev.sdklab.spotifysort.model.RawArtist;
import dev.sdklab.spotifysort.model.RawTrack;
import dev.sdklab.spotifysort.model.TaggedTrack;
import lombok.RequiredArgsConstructor;
import se.michaelthelin.spotify.model_objects.specification.AudioFeatures;

/**
 * Orchestrates GenreTagger and MoodTagger to produce a fully-tagged track.
 */
@Component
@RequiredArgsConstructor
public class TrackTagger {

    private final GenreTagger genreTagger;
    private final MoodTagger moodTagger;

    public TaggedTrack tag(RawTrack track, List<RawArtist> artists, AudioFeatures audioFeatures) {
        Set<String> tags = new HashSet<>();
        tags.addAll(genreTagger.tag(artists));

        // If the genres API is unavailable (Spotify restricted for new apps), fall back to
        // artist names as tags so SyncSuggestEngine can still group tracks meaningfully.
        if (tags.isEmpty() && !track.artistNames().isEmpty()) {
            track.artistNames().stream()
                    .filter(n -> n != null && !n.isBlank())
                    .map(String::trim)
                    .forEach(tags::add);
        }

        tags.addAll(moodTagger.tag(audioFeatures));

        return new TaggedTrack(
                track.id(),
                track.name(),
                track.uri(),
                tags
        );
    }
}
