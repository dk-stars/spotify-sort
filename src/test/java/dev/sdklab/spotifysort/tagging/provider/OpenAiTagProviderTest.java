package dev.sdklab.spotifysort.tagging.provider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.Mock;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.sdklab.spotifysort.model.RawTrack;
import dev.sdklab.spotifysort.tagging.api.LlmTrackResult;
import dev.sdklab.spotifysort.tagging.api.ProviderLookupStatus;
import dev.sdklab.spotifysort.tagging.api.TagResult;
import dev.sdklab.spotifysort.tagging.api.TagSource;
import dev.sdklab.spotifysort.tagging.api.TagType;

@ExtendWith(MockitoExtension.class)
class OpenAiTagProviderTest {

    @Mock
    private RestTemplate restTemplate;

    private OpenAiTagProvider provider;

    @BeforeEach
    void setUp() {
        provider = new OpenAiTagProvider(
                restTemplate,
                new ObjectMapper(),
                "test-api-key",
                "gpt-4o-mini",
                "https://api.openai.com/v1",
                20, 2, 2
        );
    }

    @Test
    void getTagsForTracks_parsesTaggedTrack() throws Exception {
        RawTrack track = new RawTrack(
                "track-1", "Paranoid Android", "spotify:track:1",
                List.of("artist-1"), List.of("Radiohead"), null,
                "The Bends", "1995-03-13", "day", "GBAYE9500006", 382000, false
        );

        String openAiResponse = buildFakeResponse("track-1", "TAGGED",
                List.of("alternative", "rock"), List.of("melancholy"), "ISRC", "KNOWN_MATCH", 0.95);

        when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(openAiResponse);

        List<LlmTrackResult> results = provider.getTagsForTracks(List.of(track));

        assertThat(results).hasSize(1);
        LlmTrackResult result = results.get(0);
        assertThat(result.trackSpotifyId()).isEqualTo("track-1");
        assertThat(result.status()).isEqualTo(ProviderLookupStatus.HIT);
        assertThat(result.matchStrategy()).isEqualTo("ISRC");
        assertThat(result.confidence()).isEqualTo(0.95);

        List<TagResult> tags = result.tags();
        assertThat(tags).hasSize(3); // 2 genres + 1 mood
        assertThat(tags).filteredOn(t -> t.type() == TagType.GENRE)
                .extracting(TagResult::value)
                .containsExactlyInAnyOrder("alternative", "rock");
        assertThat(tags).filteredOn(t -> t.type() == TagType.MOOD)
                .extracting(TagResult::value)
                .containsExactly("melancholy");
        assertThat(tags).allMatch(t -> t.source() == TagSource.LLM_GPT);
    }

    @Test
    void getTagsForTracks_unknownTrack_returnsUnknownStatus() throws Exception {
        RawTrack track = new RawTrack(
                "track-2", "Intro", "spotify:track:2",
                List.of("artist-2"), List.of("Unknown Artist"), null
        );

        String openAiResponse = buildFakeResponse("track-2", "UNKNOWN",
                List.of(), List.of(), "NONE", "GENERIC_TITLE_AMBIGUOUS", 0.1);

        when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(openAiResponse);

        List<LlmTrackResult> results = provider.getTagsForTracks(List.of(track));

        assertThat(results).hasSize(1);
        LlmTrackResult result = results.get(0);
        assertThat(result.trackSpotifyId()).isEqualTo("track-2");
        assertThat(result.status()).isEqualTo(ProviderLookupStatus.UNKNOWN);
        assertThat(result.tags()).isEmpty();
    }

    @Test
    void getTagsForTracks_tagsOutsideVocab_areDropped() throws Exception {
        RawTrack track = new RawTrack(
                "track-3", "Some Song", "spotify:track:3",
                List.of("artist-3"), List.of("Some Artist"), null
        );

        String maliciousTag = "invented-genre-xyz";
        String openAiResponse = "{"
                + "\"id\":\"chatcmpl-1\","
                + "\"choices\":[{\"message\":{\"content\":\""
                + escapeJson("{\"tracks\":[{\"clientTrackId\":\"track-3\",\"status\":\"TAGGED\","
                        + "\"genres\":[\"" + maliciousTag + "\",\"pop\"],"
                        + "\"moods\":[\"chill\"],"
                        + "\"confidence\":0.9,\"matchStrategy\":\"ARTIST_TRACK_ONLY\","
                        + "\"reasonCode\":\"KNOWN_MATCH\"}]}")
                + "\"}}]}";

        when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(openAiResponse);

        List<LlmTrackResult> results = provider.getTagsForTracks(List.of(track));
        List<TagResult> tags = results.get(0).tags();

        assertThat(tags).filteredOn(t -> t.type() == TagType.GENRE)
                .extracting(TagResult::value)
                .containsExactly("pop");
        // "chill" is not in MOOD_VOCAB
        assertThat(tags).filteredOn(t -> t.type() == TagType.MOOD).isEmpty();
    }

    @Test
    void getTagsForTracks_httpFailure_returnsErrorStatus() {
        RawTrack track = new RawTrack(
                "track-4", "Song", "spotify:track:4",
                List.of("artist-4"), List.of("Artist"), null
        );

        when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new org.springframework.web.client.RestClientException("connection refused"));

        List<LlmTrackResult> results = provider.getTagsForTracks(List.of(track));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).status()).isEqualTo(ProviderLookupStatus.ERROR);
        assertThat(results.get(0).tags()).isEmpty();
    }

    @Test
    void getTagsForTracks_enforcesMaxGenresMoods() throws Exception {
        RawTrack track = new RawTrack(
                "track-5", "Big Hit", "spotify:track:5",
                List.of("artist-5"), List.of("Big Artist"), null
        );

        String openAiResponse = "{"
                + "\"id\":\"chatcmpl-2\","
                + "\"choices\":[{\"message\":{\"content\":\""
                + escapeJson("{\"tracks\":[{\"clientTrackId\":\"track-5\",\"status\":\"TAGGED\","
                        + "\"genres\":[\"pop\",\"rock\",\"electronic\"],"
                        + "\"moods\":[\"energetic\",\"upbeat\",\"dark\"],"
                        + "\"confidence\":0.9,\"matchStrategy\":\"ARTIST_TRACK_ONLY\","
                        + "\"reasonCode\":\"KNOWN_MATCH\"}]}")
                + "\"}}]}";

        when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(openAiResponse);

        List<LlmTrackResult> results = provider.getTagsForTracks(List.of(track));
        List<TagResult> tags = results.get(0).tags();

        assertThat(tags).filteredOn(t -> t.type() == TagType.GENRE).hasSize(2);
        assertThat(tags).filteredOn(t -> t.type() == TagType.MOOD).hasSize(2);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String buildFakeResponse(String clientTrackId, String status,
            List<String> genres, List<String> moods,
            String matchStrategy, String reasonCode, double confidence) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String genresJson = mapper.writeValueAsString(genres);
        String moodsJson = mapper.writeValueAsString(moods);
        String inner = "{\"tracks\":[{\"clientTrackId\":\"" + clientTrackId + "\","
                + "\"status\":\"" + status + "\","
                + "\"genres\":" + genresJson + ","
                + "\"moods\":" + moodsJson + ","
                + "\"confidence\":" + confidence + ","
                + "\"matchStrategy\":\"" + matchStrategy + "\","
                + "\"reasonCode\":\"" + reasonCode + "\"}]}";
        return "{\"id\":\"chatcmpl-test\","
                + "\"choices\":[{\"message\":{\"content\":\""
                + escapeJson(inner)
                + "\"}}]}";
    }

    private String escapeJson(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
