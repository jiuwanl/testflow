package com.testflow.persistence;

import java.time.Instant;

public record RunFilter(
    String sourceType,
    Long sourceId,
    Long environmentId,
    String state,
    String outcome,
    Instant createdFrom,
    Instant createdTo) {}
