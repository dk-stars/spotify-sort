package dev.sdklab.spotifysort.tagging.service;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import dev.sdklab.spotifysort.model.TrackTag;
import dev.sdklab.spotifysort.repository.ArtistTagRepository;
import dev.sdklab.spotifysort.repository.TrackProviderStateRepository;
import dev.sdklab.spotifysort.repository.TrackTagRepository;
import dev.sdklab.spotifysort.tagging.api.ProviderLookupStatus;
import dev.sdklab.spotifysort.tagging.api.TagResult;
import dev.sdklab.spotifysort.tagging.api.TagSource;
import dev.sdklab.spotifysort.tagging.api.TagType;
import dev.sdklab.spotifysort.tagging.norm.TagNormalizer;

@DataJpaTest
@Import({TagCacheService.class, TagNormalizer.class})
class TagCacheServiceTest {

    @Autowired
    private TagCacheService tagCacheService;

    @Autowired
    private TrackTagRepository trackTagRepository;

    @Autowired
    private ArtistTagRepository artistTagRepository;

    @Autowired
    private TrackProviderStateRepository trackProviderStateRepository;

    private static final String TRACK_ID = "spotify:track:abc123";
    private static final String ARTIST_ID = "spotify:artist:xyz456";

    @Test
    void storeAndRetrieve_trackTags_roundTrip() {
        List<TagResult> tags = List.of(
                new TagResult("indie rock", TagType.GENRE, TagSource.LAST_FM, 80),
                new TagResult("chill", TagType.MOOD, TagSource.LAST_FM, 40)
        );

        tagCacheService.storeTrackTags(TRACK_ID, TagSource.LAST_FM, tags);
        List<TagResult> retrieved = tagCacheService.getTrackTags(TRACK_ID, TagSource.LAST_FM);

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

        List<TagResult> retrieved = tagCacheService.getTrackTags(TRACK_ID, TagSource.LAST_FM);
        assertThat(retrieved).extracting(TagResult::value).containsExactly("pop");
    }

    @Test
    void storeAndRetrieve_artistTags_roundTrip() {
        List<TagResult> tags = List.of(new TagResult("electronic", TagType.GENRE, TagSource.LAST_FM, 90));
        tagCacheService.storeArtistTags(ARTIST_ID, TagSource.LAST_FM, tags);

        assertThat(tagCacheService.isArtistTagsCacheValid(ARTIST_ID, TagSource.LAST_FM)).isTrue();
        List<TagResult> retrieved = tagCacheService.getArtistTags(ARTIST_ID, TagSource.LAST_FM);
        assertThat(retrieved).extracting(TagResult::value).containsExactly("electronic");
    }

    @Test
    void getTrackTags_normalizesLegacyCachedVariants() {
        trackTagRepository.save(TrackTag.builder()
                .trackSpotifyId(TRACK_ID)
                .value("hip-hop")
                .type(TagType.GENRE)
                .source(TagSource.LAST_FM)
                .weight(70)
                .cachedAt(java.time.Instant.now())
                .build());
        trackTagRepository.save(TrackTag.builder()
                .trackSpotifyId(TRACK_ID)
                .value("hip hop")
                .type(TagType.GENRE)
                .source(TagSource.LAST_FM)
                .weight(80)
                .cachedAt(java.time.Instant.now())
                .build());

        List<TagResult> retrieved = tagCacheService.getTrackTags(TRACK_ID, TagSource.LAST_FM);

        assertThat(retrieved).hasSize(1);
        assertThat(retrieved.get(0).value()).isEqualTo("hip hop");
        assertThat(retrieved.get(0).weight()).isEqualTo(80);
    }

    @Test
    void storeProviderState_and_isProviderStateFresh_roundTrip() {
        tagCacheService.storeProviderState(
                TRACK_ID, TagSource.LLM_GPT, ProviderLookupStatus.UNKNOWN,
                "gpt-4o-mini", "NONE", "GENERIC_TITLE_AMBIGUOUS", 0.1);

        assertThat(tagCacheService.isProviderStateFresh(TRACK_ID, TagSource.LLM_GPT)).isTrue();
        assertThat(tagCacheService.getProviderStatus(TRACK_ID, TagSource.LLM_GPT))
                .contains(ProviderLookupStatus.UNKNOWN);
        // Last.fm state should be independent
        assertThat(tagCacheService.isProviderStateFresh(TRACK_ID, TagSource.LAST_FM)).isFalse();
    }

    @Test
    void storeProviderState_updatesExistingRecord() {
        tagCacheService.storeProviderState(
                TRACK_ID, TagSource.LLM_GPT, ProviderLookupStatus.UNKNOWN,
                "gpt-4o-mini", "NONE", "NO_CATALOG_MEMORY", 0.0);
        tagCacheService.storeProviderState(
                TRACK_ID, TagSource.LLM_GPT, ProviderLookupStatus.HIT,
                "gpt-4o-mini", "ISRC", "KNOWN_MATCH", 0.95);

        assertThat(trackProviderStateRepository.findAll()).hasSize(1);
        assertThat(tagCacheService.getProviderStatus(TRACK_ID, TagSource.LLM_GPT))
                .contains(ProviderLookupStatus.HIT);
    }
}
