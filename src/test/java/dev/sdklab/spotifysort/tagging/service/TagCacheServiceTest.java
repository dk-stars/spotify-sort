package dev.sdklab.spotifysort.tagging.service;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import dev.sdklab.spotifysort.repository.ArtistTagRepository;
import dev.sdklab.spotifysort.repository.TrackTagRepository;
import dev.sdklab.spotifysort.tagging.api.TagResult;
import dev.sdklab.spotifysort.tagging.api.TagSource;
import dev.sdklab.spotifysort.tagging.api.TagType;

@DataJpaTest
@Import(TagCacheService.class)
class TagCacheServiceTest {

    @Autowired
    private TagCacheService tagCacheService;

    @Autowired
    private TrackTagRepository trackTagRepository;

    @Autowired
    private ArtistTagRepository artistTagRepository;

    private static final String TRACK_ID = "spotify:track:abc123";
    private static final String ARTIST_ID = "spotify:artist:xyz456";

    @Test
    void storeAndRetrieve_trackTags_roundTrip() {
        List<TagResult> tags = List.of(
                new TagResult("indie rock", TagType.GENRE, TagSource.LAST_FM, 80),
                new TagResult("chill", TagType.MOOD, TagSource.LAST_FM, 40)
        );

        tagCacheService.storeTrackTags(TRACK_ID, TagSource.LAST_FM, tags);
        List<TagResult> retrieved = tagCacheService.getTrackTags(TRACK_ID);

        assertThat(retrieved).hasSize(2);
        assertThat(retrieved).extracting(TagResult::value).containsExactlyInAnyOrder("indie rock", "chill");
    }

    @Test
    void cacheValid_afterStore_returnsTrue() {
        List<TagResult> tags = List.of(new TagResult("jazz", TagType.GENRE, TagSource.LAST_FM, 60));
        tagCacheService.storeTrackTags(TRACK_ID, TagSource.LAST_FM, tags);

        assertThat(tagCacheService.isTrackTagsCacheValid(TRACK_ID, TagSource.LAST_FM)).isTrue();
    }

    @Test
    void cacheValid_whenNoData_returnsFalse() {
        assertThat(tagCacheService.isTrackTagsCacheValid("nonexistent:id", TagSource.LAST_FM)).isFalse();
    }

    @Test
    void storeTrackTags_replacesExistingForSameSource() {
        List<TagResult> first = List.of(new TagResult("rock", TagType.GENRE, TagSource.LAST_FM, 50));
        List<TagResult> second = List.of(new TagResult("pop", TagType.GENRE, TagSource.LAST_FM, 70));

        tagCacheService.storeTrackTags(TRACK_ID, TagSource.LAST_FM, first);
        tagCacheService.storeTrackTags(TRACK_ID, TagSource.LAST_FM, second);

        List<TagResult> retrieved = tagCacheService.getTrackTags(TRACK_ID);
        assertThat(retrieved).extracting(TagResult::value).containsExactly("pop");
    }

    @Test
    void storeAndRetrieve_artistTags_roundTrip() {
        List<TagResult> tags = List.of(new TagResult("electronic", TagType.GENRE, TagSource.LAST_FM, 90));
        tagCacheService.storeArtistTags(ARTIST_ID, TagSource.LAST_FM, tags);

        assertThat(tagCacheService.isArtistTagsCacheValid(ARTIST_ID, TagSource.LAST_FM)).isTrue();
        List<TagResult> retrieved = tagCacheService.getArtistTags(ARTIST_ID);
        assertThat(retrieved).extracting(TagResult::value).containsExactly("electronic");
    }
}
