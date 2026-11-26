package com.fixflow.backend.request;

import com.fixflow.backend.common.api.*;
import java.util.Map;
import java.util.Set;
import static com.fixflow.backend.request.RequestStatus.*;

/** FF-07 administrative transitions only. Assignment/work/confirmation actions need their own use cases. */
public final class RequestWorkflowPolicy {
    public static void validateAssignment(RequestStatus status, boolean reassignment) {
        boolean valid = reassignment ? Set.of(ASSIGNED, IN_PROGRESS, ON_HOLD).contains(status) : status == VERIFIED;
        if (!valid) throw new ConflictException("The request is not eligible for this assignment operation.");
    }
    public static void validateWork(RequestStatus from, RequestStatus to) {
        boolean valid = from == ASSIGNED && to == IN_PROGRESS || from == IN_PROGRESS && (to == ON_HOLD || to == RESOLVED)
                || from == ON_HOLD && to == IN_PROGRESS;
        if (!valid) throw new ConflictException("This technician work transition is not permitted.");
    }
    public static void validateReporter(RequestStatus from, RequestStatus to) {
        if (from != RESOLVED || (to != CONFIRMED && to != REOPENED))
            throw new ConflictException("Only a resolved request can be confirmed or reopened.");
    }
    private RequestWorkflowPolicy() { }
    private static final Map<RequestStatus, Set<RequestStatus>> ADMIN_TRANSITIONS = Map.of(
            OPEN, Set.of(VERIFIED, REJECTED), RESOLVED, Set.of(REOPENED),
            REOPENED, Set.of(VERIFIED), CONFIRMED, Set.of(CLOSED));
    public static boolean allows(RequestStatus from, RequestStatus to) {
        return from != null && to != null && ADMIN_TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }
    public static void validate(RequestStatus from, RequestStatus to, String reason,
                                boolean activeAssignment, boolean completedResolution) {
        if (!allows(from, to)) throw new ConflictException("This administrative status transition is not permitted.");
        if ((to == REJECTED || to == REOPENED) && (reason == null || reason.strip().length() < 5))
            throw new BadRequestException("A reason of at least 5 characters is required for rejection or reopening.");
        if (reason != null && reason.length() > 2000) throw new BadRequestException("Reason must not exceed 2000 characters.");
        if (activeAssignment) throw new ConflictException("Complete the assignment workflow before this administrative transition.");
        if ((from == RESOLVED || from == REOPENED || from == CONFIRMED) && !completedResolution)
            throw new ConflictException("A completed resolution assignment is required for this transition.");
    }
    public static void validatePriority(RequestStatus status) {
        if (status == CLOSED || status == REJECTED || status == RESOLVED || status == CONFIRMED)
            throw new ConflictException("Priority cannot be changed on a resolved, confirmed or terminal request.");
    }
}
