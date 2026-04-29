package dev.sdklab.spotifysort.tagging.provider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.Mock;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import dev.sdklab.spotifysort.tagging.api.TagResult;
import dev.sdklab.spotifysort.tagging.api.TagSource;
import dev.sdklab.spotifysort.tagging.api.TagType;
import dev.sdklab.spotifysort.tagging.norm.TagNormalizer;

@ExtendWith(MockitoExtension.class)
class LastFmTagProviderTest {

    @Mock
    private RestTemplate restTemplate;

    private LastFmTagProvider provider;

    @BeforeEach
    void setUp() {
        TagNormalizer normalizer = new TagNormalizer("seen live,love,favorite,awesome");
        provider = new LastFmTagProvider(restTemplate, normalizer, "test-api-key", 1, 0);
    }

    @Test
    void getTagsForTrack_returnsNormalizedTags() {
        Map<String, Object> tagMap = Map.of(
                "toptags", Map.of(
                        "tag", List.of(
                                Map.of("name", "indie rock", "count", "80"),
                                Map.of("name", "seen live", "count", "200"),  // noise — filtered
                                Map.of("name", "Chill", "count", "40")
                        )
                )
        );
        when(restTemplate.getForEntity(any(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(tagMap, HttpStatus.OK));

        List<TagResult> results = provider.getTagsForTrack("Radiohead", "Creep");

        assertThat(results).hasSize(2);
        assertThat(results).extracting(TagResult::value).containsExactlyInAnyOrder("indie rock", "chill");
        assertThat(results.stream().filter(r -> r.value().equals("chill")).findFirst())
                .isPresent()
                .get()
                .extracting(TagResult::type)
                .isEqualTo(TagType.MOOD);
        assertThat(results.stream().filter(r -> r.value().equals("indie rock")).findFirst())
                .isPresent()
                .get()
                .extracting(TagResult::type)
                .isEqualTo(TagType.GENRE);
    }

    @Test
    void getTagsForArtist_returnsNormalizedTags() {
        Map<String, Object> tagMap = Map.of(
                "toptags", Map.of(
                        "tag", Map.of("name", "electronic", "count", 90)  // single object, not array
                )
        );
        when(restTemplate.getForEntity(any(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(tagMap, HttpStatus.OK));

        List<TagResult> results = provider.getTagsForArtist("Aphex Twin");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).value()).isEqualTo("electronic");
        assertThat(results.get(0).source()).isEqualTo(TagSource.LAST_FM);
    }

    @Test
    void getTagsForTrack_onHttpError_returnsEmptyList() {
        when(restTemplate.getForEntity(any(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(null, HttpStatus.SERVICE_UNAVAILABLE));

        List<TagResult> results = provider.getTagsForTrack("Some Artist", "Some Track");

        assertThat(results).isEmpty();
    }

    @Test
    void getTagsForTrack_tagsBelow_minCount_areFiltered() {
        // provider created with minTagCount=1, but we set count=0 to trigger filter
        LastFmTagProvider strictProvider = new LastFmTagProvider(
                restTemplate,
                new TagNormalizer("seen live"),
                "test-api-key",
                10,  // minTagCount = 10
                0
        );

        Map<String, Object> tagMap = Map.of(
                "toptags", Map.of(
                        "tag", List.of(
                                Map.of("name", "indie", "count", "5")  // below minTagCount=10
                        )
                )
        );
        when(restTemplate.getForEntity(any(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(tagMap, HttpStatus.OK));

        List<TagResult> results = strictProvider.getTagsForTrack("Artist", "Track");

        assertThat(results).isEmpty();
    }
}
