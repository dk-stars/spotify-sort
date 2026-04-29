package dev.sdklab.spotifysort.tagging.norm;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import dev.sdklab.spotifysort.tagging.api.TagResult;
import dev.sdklab.spotifysort.tagging.api.TagSource;
import dev.sdklab.spotifysort.tagging.api.TagType;

@Component
public class TagNormalizer {

    private static final Set<String> MOOD_KEYWORDS = Set.of(
            "chill", "calm", "dark", "happy", "sad", "energetic",
            "melancholy", "upbeat", "aggressive", "relaxing", "relaxed",
            "party", "danceable", "angry", "peaceful", "romantic",
            "melancholic", "cheerful", "emotional", "intense", "dreamy",
            "groovy", "mellow", "feel good", "feel-good", "uplifting"
    );

    private final Set<String> noiseDenylist;

    public TagNormalizer(@Value("${lastfm.noise-tags:seen live,love,favourite,favorite,favorites,favourites,best,awesome,cool,beautiful,nice,good,great,amazing,sexy,hot,ok}") String noiseTagsCsv) {
        this.noiseDenylist = Set.copyOf(
                List.of(noiseTagsCsv.split(",")).stream()
                        .map(String::trim)
                        .map(String::toLowerCase)
                        .filter(s -> !s.isBlank())
                        .toList()
        );
    }

    /**
     * Normalizes a raw Last.fm tag string into a TagResult.
     * Returns empty Optional if the tag is in the noise denylist.
     */
    public Optional<TagResult> normalize(String rawTag, TagSource source, int weight) {
        String normalized = canonicalize(rawTag);
        if (normalized.isBlank() || noiseDenylist.contains(normalized)) {
            return Optional.empty();
        }
        TagType type = isMood(normalized) ? TagType.MOOD : TagType.GENRE;
        return Optional.of(new TagResult(normalized, type, source, weight));
    }

    private boolean isMood(String tag) {
        for (String keyword : MOOD_KEYWORDS) {
            if (tag.equals(keyword) || tag.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String canonicalize(String rawTag) {
        String normalized = rawTag.trim().toLowerCase(Locale.ROOT);
        normalized = normalized.replaceAll("[‐-―]", "-");
        normalized = normalized.replaceAll("(?<=\\p{Alnum})[-_](?=\\p{Alnum})", " ");
        normalized = normalized.replaceAll("\\s+", " ").trim();
        return normalized;
    }
}
