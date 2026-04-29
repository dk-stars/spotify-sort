package dev.sdklab.spotifysort.tagging.norm;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.sdklab.spotifysort.tagging.api.TagResult;
import dev.sdklab.spotifysort.tagging.api.TagSource;
import dev.sdklab.spotifysort.tagging.api.TagType;

class TagNormalizerTest {

    private TagNormalizer normalizer;

    @BeforeEach
    void setUp() {
        // Use the default noise-tags CSV (same as application.yml default)
        normalizer = new TagNormalizer(
                "seen live,love,favourite,favorite,favorites,favourites,best,awesome,cool"
        );
    }

    @Test
    void genreTag_isClassifiedAsGenre() {
        Optional<TagResult> result = normalizer.normalize("indie rock", TagSource.LAST_FM, 50);
        assertThat(result).isPresent();
        assertThat(result.get().type()).isEqualTo(TagType.GENRE);
        assertThat(result.get().value()).isEqualTo("indie rock");
    }

    @Test
    void moodKeyword_isClassifiedAsMood() {
        Optional<TagResult> result = normalizer.normalize("Chill", TagSource.LAST_FM, 30);
        assertThat(result).isPresent();
        assertThat(result.get().type()).isEqualTo(TagType.MOOD);
        assertThat(result.get().value()).isEqualTo("chill");
    }

    @Test
    void noiseTag_isFilteredOut() {
        Optional<TagResult> result = normalizer.normalize("seen live", TagSource.LAST_FM, 100);
        assertThat(result).isEmpty();
    }

    @Test
    void blankTag_isFilteredOut() {
        Optional<TagResult> result = normalizer.normalize("   ", TagSource.LAST_FM, 10);
        assertThat(result).isEmpty();
    }

    @Test
    void tagIsTrimmedAndLowercased() {
        Optional<TagResult> result = normalizer.normalize("  Heavy Metal  ", TagSource.LAST_FM, 20);
        assertThat(result).isPresent();
        assertThat(result.get().value()).isEqualTo("heavy metal");
    }

    @Test
    void weightIsPreserved() {
        Optional<TagResult> result = normalizer.normalize("jazz", TagSource.LAST_FM, 42);
        assertThat(result).isPresent();
        assertThat(result.get().weight()).isEqualTo(42);
    }
}
