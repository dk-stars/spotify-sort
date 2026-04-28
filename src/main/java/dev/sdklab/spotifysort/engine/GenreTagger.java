package dev.sdklab.spotifysort.engine;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import se.michaelthelin.spotify.model_objects.specification.Artist;

/**
 * Extracts normalized genre tags from a list of full Artist objects.
 * Each artist may contribute multiple genres; duplicates are removed.
 */
@Component
public class GenreTagger {

    public Set<String> tag(List<Artist> artists) {
        return artists.stream()
                .filter(a -> a.getGenres() != null)
                .flatMap(a -> Arrays.stream(a.getGenres()))
                .map(String::toLowerCase)
                .map(String::trim)
                .filter(g -> !g.isBlank())
                .collect(Collectors.toSet());
    }
}
