package com.fixflow.backend.request;

import java.time.Instant;

/** Public tracking data deliberately excludes actor personal data and administrative reasons. */
public record StatusHistoryResponse(Long id, RequestStatus previousStatus, RequestStatus newStatus, Instant changedAt) {
    static StatusHistoryResponse from(StatusHistory history) {
        return new StatusHistoryResponse(history.getId(), history.getPreviousStatus(), history.getNewStatus(), history.getChangedAt());
    }
}
