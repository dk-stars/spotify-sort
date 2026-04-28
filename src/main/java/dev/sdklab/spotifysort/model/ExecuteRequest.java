package dev.sdklab.spotifysort.model;

import java.util.List;

public record ExecuteRequest(List<UpdateAction> updates, List<CreateAction> creates) {}
