package dev.sdklab.spotifysort.tagging.provider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.sdklab.spotifysort.model.RawTrack;
import dev.sdklab.spotifysort.tagging.api.LlmTagProvider;
import dev.sdklab.spotifysort.tagging.api.LlmTrackResult;
import dev.sdklab.spotifysort.tagging.api.ProviderLookupStatus;
import dev.sdklab.spotifysort.tagging.api.TagResult;
import dev.sdklab.spotifysort.tagging.api.TagSource;
import dev.sdklab.spotifysort.tagging.api.TagType;

/**
 * LLM-backed tag provider using the OpenAI Chat Completions API with strict JSON schema output.
 *
 * <p>Sends batches of tracks (up to {@code openai.batch-size} each) and expects the model
 * to return a structured tag response for every track in the batch. Tracks that the model
 * cannot confidently identify are returned with an UNKNOWN or AMBIGUOUS status and empty tag lists.
 *
 * <p>Activated only when {@code openai.api-key} is non-empty.
 */
@Component
@ConditionalOnProperty(name = "openai.api-key", matchIfMissing = false)
public class OpenAiTagProvider implements LlmTagProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiTagProvider.class);

    /** Closed genre vocabulary sent to the model; constrains schema enum to prevent tag explosion. */
    static final List<String> GENRE_VOCAB = List.of(
            "alternative", "ambient", "blues", "classical", "country", "dance",
            "disco", "drum and bass", "electronic", "folk", "funk", "gospel",
            "grunge", "heavy metal", "hip hop", "house", "indie", "jazz",
            "latin", "metal", "new wave", "opera", "pop", "punk", "r&b",
            "reggae", "rock", "soul", "techno", "world"
    );

    /** Closed mood vocabulary — aligned with TagNormalizer.MOOD_KEYWORDS. */
    static final List<String> MOOD_VOCAB = List.of(
            "aggressive", "calm", "cheerful", "dark", "dreamy", "energetic",
            "feel-good", "groovy", "intense", "melancholy", "mellow",
            "peaceful", "romantic", "sad", "upbeat"
    );

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;
    private final String baseUrl;
    private final int batchSize;
    private final int maxGenres;
    private final int maxMoods;

    public OpenAiTagProvider(
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @Value("${openai.api-key}") String apiKey,
            @Value("${openai.model:gpt-4o-mini}") String model,
            @Value("${openai.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${openai.batch-size:20}") int batchSize,
            @Value("${openai.max-genres:2}") int maxGenres,
            @Value("${openai.max-moods:2}") int maxMoods
    ) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.model = model;
        this.baseUrl = baseUrl;
        this.batchSize = batchSize;
        this.maxGenres = maxGenres;
        this.maxMoods = maxMoods;
    }

    @Override
    public List<LlmTrackResult> getTagsForTracks(List<RawTrack> tracks) {
        List<LlmTrackResult> results = new ArrayList<>();

        for (int i = 0; i < tracks.size(); i += batchSize) {
            List<RawTrack> batch = tracks.subList(i, Math.min(i + batchSize, tracks.size()));
            try {
                results.addAll(tagBatch(batch));
            } catch (Exception e) {
                log.warn("LLM batch failed for {} tracks starting at index {}: {}", batch.size(), i, e.getMessage());
                for (RawTrack track : batch) {
                    results.add(errorResult(track.id()));
                }
            }
        }

        return results;
    }

    private List<LlmTrackResult> tagBatch(List<RawTrack> batch) {
        String requestBody = buildRequestBody(batch);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        String responseBody;
        try {
            responseBody = restTemplate.postForObject(
                    baseUrl + "/chat/completions",
                    new HttpEntity<>(requestBody, headers),
                    String.class
            );
        } catch (RestClientException e) {
            throw new RuntimeException("OpenAI HTTP request failed: " + e.getMessage(), e);
        }

        return parseResponse(responseBody, batch);
    }

    private String buildRequestBody(List<RawTrack> batch) {
        try {
            List<Map<String, Object>> trackItems = new ArrayList<>();
            for (RawTrack track : batch) {
                Map<String, Object> item = new HashMap<>();
                item.put("clientTrackId", track.id());
                item.put("trackName", track.name());
                item.put("primaryArtist", track.artistNames().isEmpty() ? "" : track.artistNames().get(0));
                if (track.artistNames().size() > 1) {
                    item.put("allArtists", track.artistNames());
                }
                if (track.albumName() != null) item.put("albumName", track.albumName());
                if (track.releaseDate() != null) item.put("releaseDate", track.releaseDate());
                if (track.releaseDatePrecision() != null) item.put("releaseDatePrecision", track.releaseDatePrecision());
                if (track.isrc() != null) item.put("isrc", track.isrc());
                if (track.durationMs() > 0) item.put("durationMs", track.durationMs());
                trackItems.add(item);
            }

            String systemPrompt = buildSystemPrompt();
            String userContent = "Tag the following tracks:\n" + objectMapper.writeValueAsString(trackItems);

            Map<String, Object> request = new HashMap<>();
            request.put("model", model);
            request.put("temperature", 0);
            request.put("store", false);
            request.put("messages", List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userContent)
            ));
            request.put("response_format", buildResponseFormat());

            return objectMapper.writeValueAsString(request);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build OpenAI request body: " + e.getMessage(), e);
        }
    }

    private String buildSystemPrompt() {
        return "You are a music metadata assistant. For each track provided, return genre and mood tags " +
                "using ONLY the values from the supplied closed vocabulary. " +
                "Return at most " + maxGenres + " genres and " + maxMoods + " moods per track. " +
                "If you are not confident this is the exact track, return status=UNKNOWN with empty arrays. " +
                "Never infer from track title alone. Never invent tags outside the closed vocabulary. " +
                "Prefer abstention (UNKNOWN) over guessing. " +
                "Do not include explanations or prose in your response.";
    }

    private Map<String, Object> buildResponseFormat() {
        Map<String, Object> genreItems = Map.of("type", "string", "enum", GENRE_VOCAB);
        Map<String, Object> moodItems = Map.of("type", "string", "enum", MOOD_VOCAB);

        Map<String, Object> trackProperties = new HashMap<>();
        trackProperties.put("clientTrackId", Map.of("type", "string"));
        trackProperties.put("status", Map.of("type", "string", "enum", List.of("TAGGED", "UNKNOWN", "AMBIGUOUS")));
        trackProperties.put("genres", Map.of("type", "array", "items", genreItems));
        trackProperties.put("moods", Map.of("type", "array", "items", moodItems));
        trackProperties.put("confidence", Map.of("type", "number"));
        trackProperties.put("matchStrategy", Map.of("type", "string",
                "enum", List.of("ISRC", "ARTIST_TRACK_ALBUM", "ARTIST_TRACK_RELEASE", "ARTIST_TRACK_ONLY", "NONE")));
        trackProperties.put("reasonCode", Map.of("type", "string",
                "enum", List.of("KNOWN_MATCH", "NOT_ENOUGH_EVIDENCE", "GENERIC_TITLE_AMBIGUOUS", "NO_CATALOG_MEMORY")));

        List<String> requiredTrackFields = List.of(
                "clientTrackId", "status", "genres", "moods", "confidence", "matchStrategy", "reasonCode");

        Map<String, Object> trackSchema = new HashMap<>();
        trackSchema.put("type", "object");
        trackSchema.put("additionalProperties", false);
        trackSchema.put("required", requiredTrackFields);
        trackSchema.put("properties", trackProperties);

        Map<String, Object> rootProperties = new HashMap<>();
        rootProperties.put("tracks", Map.of(
                "type", "array",
                "items", trackSchema
        ));

        Map<String, Object> rootSchema = new HashMap<>();
        rootSchema.put("type", "object");
        rootSchema.put("additionalProperties", false);
        rootSchema.put("required", List.of("tracks"));
        rootSchema.put("properties", rootProperties);

        Map<String, Object> jsonSchema = new HashMap<>();
        jsonSchema.put("name", "track_tags_response");
        jsonSchema.put("strict", true);
        jsonSchema.put("schema", rootSchema);

        return Map.of("type", "json_schema", "json_schema", jsonSchema);
    }

    private List<LlmTrackResult> parseResponse(String responseBody, List<RawTrack> batch) {
        // Index batch by id so we can fill in every track — including those the model skipped
        Map<String, RawTrack> batchById = new HashMap<>();
        for (RawTrack t : batch) batchById.put(t.id(), t);

        Map<String, LlmTrackResult> parsed = new HashMap<>();

        try {
            JsonNode root = objectMapper.readTree(responseBody);

            if (root.has("error")) {
                log.warn("OpenAI API error: {}", root.path("error").path("message").asText());
                // All tracks in batch get ERROR status
                return batch.stream().map(t -> errorResult(t.id())).toList();
            }

            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.isNull()) {
                JsonNode refusal = root.path("choices").path(0).path("message").path("refusal");
                if (!refusal.isMissingNode() && !refusal.isNull()) {
                    log.warn("OpenAI model refused the request: {}", refusal.asText());
                    return batch.stream().map(t -> refusedResult(t.id())).toList();
                }
                return batch.stream().map(t -> errorResult(t.id())).toList();
            }

            JsonNode parsedContent = objectMapper.readTree(content.asText());
            JsonNode tracksNode = parsedContent.path("tracks");
            if (!tracksNode.isArray()) {
                log.warn("OpenAI response missing 'tracks' array");
                return batch.stream().map(t -> errorResult(t.id())).toList();
            }

            for (JsonNode trackNode : tracksNode) {
                String clientTrackId = trackNode.path("clientTrackId").asText(null);
                if (clientTrackId == null) continue;

                String statusStr = trackNode.path("status").asText("UNKNOWN");
                String matchStrategy = trackNode.path("matchStrategy").asText("NONE");
                String reasonCode = trackNode.path("reasonCode").asText("NO_CATALOG_MEMORY");
                double confidence = trackNode.path("confidence").asDouble(0.0);

                ProviderLookupStatus status;
                List<TagResult> tags = List.of();

                switch (statusStr) {
                    case "TAGGED" -> {
                        status = ProviderLookupStatus.HIT;
                        tags = extractTags(trackNode);
                    }
                    case "AMBIGUOUS" -> status = ProviderLookupStatus.AMBIGUOUS;
                    default -> status = ProviderLookupStatus.UNKNOWN;
                }

                parsed.put(clientTrackId, new LlmTrackResult(
                        clientTrackId, status, tags, matchStrategy, reasonCode, confidence, model));
            }

        } catch (Exception e) {
            log.warn("Failed to parse OpenAI response: {}", e.getMessage());
            return batch.stream().map(t -> errorResult(t.id())).toList();
        }

        // Ensure every batch track has a result; fill missing ones with UNKNOWN
        return batch.stream()
                .map(t -> parsed.getOrDefault(t.id(), unknownResult(t.id())))
                .toList();
    }

    private List<TagResult> extractTags(JsonNode trackNode) {
        List<TagResult> tags = new ArrayList<>();
        int count = 0;
        for (JsonNode genreNode : trackNode.path("genres")) {
            if (count >= maxGenres) break;
            String genre = genreNode.asText(null);
            if (genre != null && GENRE_VOCAB.contains(genre)) {
                tags.add(new TagResult(genre, TagType.GENRE, TagSource.LLM_GPT, 80));
                count++;
            }
        }
        count = 0;
        for (JsonNode moodNode : trackNode.path("moods")) {
            if (count >= maxMoods) break;
            String mood = moodNode.asText(null);
            if (mood != null && MOOD_VOCAB.contains(mood)) {
                tags.add(new TagResult(mood, TagType.MOOD, TagSource.LLM_GPT, 80));
                count++;
            }
        }
        return Collections.unmodifiableList(tags);
    }

    private LlmTrackResult unknownResult(String trackId) {
        return new LlmTrackResult(trackId, ProviderLookupStatus.UNKNOWN, List.of(),
                "NONE", "NO_CATALOG_MEMORY", 0.0, model);
    }

    private LlmTrackResult refusedResult(String trackId) {
        return new LlmTrackResult(trackId, ProviderLookupStatus.REFUSED, List.of(),
                "NONE", "NO_CATALOG_MEMORY", 0.0, model);
    }

    private LlmTrackResult errorResult(String trackId) {
        return new LlmTrackResult(trackId, ProviderLookupStatus.ERROR, List.of(),
                "NONE", "NO_CATALOG_MEMORY", 0.0, model);
    }
}

