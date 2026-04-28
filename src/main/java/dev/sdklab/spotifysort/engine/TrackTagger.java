package dev.sdklab.spotifysort.engine;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import dev.sdklab.spotifysort.model.TaggedTrack;
import lombok.RequiredArgsConstructor;
import se.michaelthelin.spotify.model_objects.specification.Artist;
import se.michaelthelin.spotify.model_objects.specification.AudioFeatures;
import se.michaelthelin.spotify.model_objects.specification.Track;

/**
 * Orchestrates GenreTagger and MoodTagger to produce a fully-tagged track.
 */
@Component
@RequiredArgsConstructor
public class TrackTagger {

    private final GenreTagger genreTagger;
    private final MoodTagger moodTagger;

    public TaggedTrack tag(Track track, List<Artist> artists, AudioFeatures audioFeatures) {
        Set<String> tags = new HashSet<>();
        tags.addAll(genreTagger.tag(artists));
        tags.addAll(moodTagger.tag(audioFeatures));

        return new TaggedTrack(
                track.getId(),
                track.getName(),
                "spotify:track:" + track.getId(),
                tags
        );
    }
}
