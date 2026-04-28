package dev.sdklab.spotifysort.engine;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import dev.sdklab.spotifysort.model.RawArtist;

/**
 * Extracts normalized genre tags from a list of RawArtist objects.
 * Each artist may contribute multiple genres; duplicates are removed.
 */
@Component
public class GenreTagger {

    public Set<String> tag(List<RawArtist> artists) {
        return artists.stream()
                .filter(a -> a.genres() != null)
                .flatMap(a -> a.genres().stream())
                .map(String::toLowerCase)
                .map(String::trim)
                .filter(g -> !g.isBlank())
                .collect(Collectors.toSet());
    }
}
