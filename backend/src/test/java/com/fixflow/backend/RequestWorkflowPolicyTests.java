package com.fixflow.backend;

import com.fixflow.backend.common.api.*;
import com.fixflow.backend.request.*;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RequestWorkflowPolicyTests {
    @Test void assignmentAndReporterOperationsUseTheirOwnCentralizedPolicies() {
        for(var status:RequestStatus.values()) {
            if(status==RequestStatus.VERIFIED) assertDoesNotThrow(()->RequestWorkflowPolicy.validateAssignment(status,false));
            else assertThrows(ConflictException.class,()->RequestWorkflowPolicy.validateAssignment(status,false));
            if(Set.of(RequestStatus.ASSIGNED,RequestStatus.IN_PROGRESS,RequestStatus.ON_HOLD).contains(status))
                assertDoesNotThrow(()->RequestWorkflowPolicy.validateAssignment(status,true));
            else assertThrows(ConflictException.class,()->RequestWorkflowPolicy.validateAssignment(status,true));
            for(var target:Set.of(RequestStatus.CONFIRMED,RequestStatus.REOPENED)) {
                if(status==RequestStatus.RESOLVED) assertDoesNotThrow(()->RequestWorkflowPolicy.validateReporter(status,target));
                else assertThrows(ConflictException.class,()->RequestWorkflowPolicy.validateReporter(status,target));
            }
        }
    }
    @Test void allTechnicianWorkEdgesAreExplicitAndCannotSkipAssignmentOrResolution() {
        var permitted=Set.of("ASSIGNED:IN_PROGRESS","IN_PROGRESS:ON_HOLD","ON_HOLD:IN_PROGRESS","IN_PROGRESS:RESOLVED");
        for(var from:RequestStatus.values()) for(var to:RequestStatus.values()) {
            if(permitted.contains(from+":"+to)) assertDoesNotThrow(()->RequestWorkflowPolicy.validateWork(from,to));
            else assertThrows(ConflictException.class,()->RequestWorkflowPolicy.validateWork(from,to));
        }
    }
    @Test void everyStatusPairHasAnExplicitAllowOrDenyDecision() {
        var allowed = Set.of("OPEN:VERIFIED", "OPEN:REJECTED", "RESOLVED:REOPENED", "REOPENED:VERIFIED", "CONFIRMED:CLOSED");
        for (var from : RequestStatus.values()) for (var to : RequestStatus.values()) {
            assertEquals(allowed.contains(from + ":" + to), RequestWorkflowPolicy.allows(from, to), from + " -> " + to);
            if (allowed.contains(from + ":" + to))
                assertDoesNotThrow(() -> RequestWorkflowPolicy.validate(from, to, "Reviewed request", false, true));
            else assertThrows(ConflictException.class, () -> RequestWorkflowPolicy.validate(from, to, "Reviewed request", false, true));
        }
    }
    @Test void rejectionAndReopeningNeedMeaningfulBoundedReasons() {
        assertThrows(BadRequestException.class, () -> RequestWorkflowPolicy.validate(RequestStatus.OPEN, RequestStatus.REJECTED, null, false, false));
        assertThrows(BadRequestException.class, () -> RequestWorkflowPolicy.validate(RequestStatus.RESOLVED, RequestStatus.REOPENED, "bad", false, true));
        assertThrows(BadRequestException.class, () -> RequestWorkflowPolicy.validate(RequestStatus.OPEN, RequestStatus.VERIFIED, "x".repeat(2001), false, false));
    }
    @Test void assignmentEvidenceIsRequiredAndActiveEpisodesCannotBeBypassed() {
        for (var from : Set.of(RequestStatus.RESOLVED, RequestStatus.REOPENED, RequestStatus.CONFIRMED)) {
            var to = from == RequestStatus.RESOLVED ? RequestStatus.REOPENED : from == RequestStatus.REOPENED ? RequestStatus.VERIFIED : RequestStatus.CLOSED;
            assertThrows(ConflictException.class, () -> RequestWorkflowPolicy.validate(from, to, "Reason", false, false));
            assertThrows(ConflictException.class, () -> RequestWorkflowPolicy.validate(from, to, "Reason", true, true));
        }
        assertThrows(ConflictException.class, () -> RequestWorkflowPolicy.validate(RequestStatus.OPEN, RequestStatus.VERIFIED, null, true, false));
    }
    @Test void priorityIsEditableOnlyOnActiveUnresolvedStates() {
        for (var status : RequestStatus.values()) {
            if (Set.of(RequestStatus.RESOLVED, RequestStatus.CONFIRMED, RequestStatus.CLOSED, RequestStatus.REJECTED).contains(status))
                assertThrows(ConflictException.class, () -> RequestWorkflowPolicy.validatePriority(status));
            else assertDoesNotThrow(() -> RequestWorkflowPolicy.validatePriority(status));
        }
    }
}
