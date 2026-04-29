package dev.sdklab.spotifysort.tagging.api;

public record TagResult(
        String value,
        TagType type,
        TagSource source,
        int weight
) {}
