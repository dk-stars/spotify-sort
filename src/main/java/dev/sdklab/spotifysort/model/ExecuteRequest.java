package dev.sdklab.spotifysort.model;

import java.util.List;

public record ExecuteRequest(Long scanJobId, boolean deleteFromSources, List<UpdateAction> updates, List<CreateAction> creates) {}
