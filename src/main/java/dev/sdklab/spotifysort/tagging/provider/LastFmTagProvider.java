package dev.sdklab.spotifysort.tagging.provider;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.google.common.util.concurrent.RateLimiter;

import dev.sdklab.spotifysort.tagging.api.TagProvider;
import dev.sdklab.spotifysort.tagging.api.TagResult;
import dev.sdklab.spotifysort.tagging.api.TagSource;
import dev.sdklab.spotifysort.tagging.norm.TagNormalizer;

@Component
public class LastFmTagProvider implements TagProvider {

    private static final Logger log = LoggerFactory.getLogger(LastFmTagProvider.class);
    private static final String BASE_URL = "http://ws.audioscrobbler.com/2.0/";

    private final RestTemplate restTemplate;
    private final TagNormalizer tagNormalizer;
    private final String apiKey;
    private final int minTagCount;
    private final RateLimiter rateLimiter;

    public LastFmTagProvider(
            RestTemplate restTemplate,
            TagNormalizer tagNormalizer,
            @Value("${lastfm.api-key}") String apiKey,
            @Value("${lastfm.min-tag-count:5}") int minTagCount,
            @Value("${lastfm.rate-limit-ms:200}") long rateLimitMs
    ) {
        this.restTemplate = restTemplate;
        this.tagNormalizer = tagNormalizer;
        this.apiKey = apiKey;
        this.minTagCount = minTagCount;
        this.rateLimiter = rateLimitMs <= 0 ? null : RateLimiter.create(1000d / rateLimitMs);
    }

    @Override
    public List<TagResult> getTagsForTrack(String artistName, String trackName) {
        URI uri = UriComponentsBuilder.fromHttpUrl(BASE_URL)
                .queryParam("method", "track.getTopTags")
                .queryParam("artist", artistName)
                .queryParam("track", trackName)
                .queryParam("api_key", apiKey)
                .queryParam("format", "json")
                .queryParam("autocorrect", 1)
                .build()
                .encode()
                .toUri();
        return fetchAndParse(uri, "track", artistName + " - " + trackName);
    }

    @Override
    public List<TagResult> getTagsForArtist(String artistName) {
        URI uri = UriComponentsBuilder.fromHttpUrl(BASE_URL)
                .queryParam("method", "artist.getTopTags")
                .queryParam("artist", artistName)
                .queryParam("api_key", apiKey)
                .queryParam("format", "json")
                .queryParam("autocorrect", 1)
                .build()
                .encode()
                .toUri();
        return fetchAndParse(uri, "artist", artistName);
    }

    @SuppressWarnings("unchecked")
    private List<TagResult> fetchAndParse(URI uri, String context, String label) {
        throttle();
        try {
            ResponseEntity<Map> response = restTemplate.getForEntity(uri, Map.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("Last.fm non-success for {} '{}': {}", context, label, response.getStatusCode());
                return List.of();
            }
            return parseTags(response.getBody());
        } catch (RestClientException e) {
            log.warn("Last.fm request failed for {} '{}': {}", context, label, e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<TagResult> parseTags(Map<?, ?> body) {
        try {
            Map<?, ?> topTags = (Map<?, ?>) body.get("toptags");
            if (topTags == null) return List.of();
            Object tagEntry = topTags.get("tag");
            if (tagEntry == null) return List.of();

            List<?> tagList = (tagEntry instanceof List) ? (List<?>) tagEntry : List.of(tagEntry);
            List<TagResult> results = new ArrayList<>();
            for (Object item : tagList) {
                if (!(item instanceof Map)) continue;
                Map<?, ?> tag = (Map<?, ?>) item;
                String name = (String) tag.get("name");
                int count = parseCount(tag.get("count"));
                if (name == null || count < minTagCount) continue;
                tagNormalizer.normalize(name, TagSource.LAST_FM, count)
                        .ifPresent(results::add);
            }
            return List.copyOf(results);
        } catch (ClassCastException e) {
            log.warn("Unexpected Last.fm response structure: {}", e.getMessage());
            return List.of();
        }
    }

    private int parseCount(Object countObj) {
        if (countObj instanceof Number n) return n.intValue();
        if (countObj instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    private void throttle() {
        if (rateLimiter != null) {
            rateLimiter.acquire();
        }
    }
}
