package dev.sdklab.spotifysort.engine;

import java.util.HashSet;
import java.util.Set;

import org.springframework.stereotype.Component;

import se.michaelthelin.spotify.model_objects.specification.AudioFeatures;

/**
 * Assigns mood/style tags deterministically from Spotify audio features.
 * A single track can receive multiple mood tags.
 */
@Component
public class MoodTagger {

    public Set<String> tag(AudioFeatures features) {
        Set<String> tags = new HashSet<>();
        if (features == null) return tags;

        if (features.getAcousticness() > 0.7f)                              tags.add("calm");
        if (features.getInstrumentalness() > 0.5f)                          tags.add("no-vocal");
        if (features.getEnergy() > 0.8f && features.getTempo() > 140f)      tags.add("high-bpm");
        if (features.getDanceability() > 0.7f)                              tags.add("danceable");
        if (features.getValence() > 0.7f)                                   tags.add("happy");
        if (features.getValence() < 0.3f)                                   tags.add("dark");

        return tags;
    }
}
