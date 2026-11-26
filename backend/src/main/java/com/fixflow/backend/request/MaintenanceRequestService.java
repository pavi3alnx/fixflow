package com.fixflow.backend.request;

import com.fixflow.backend.category.CategoryRepository;
import com.fixflow.backend.common.api.*;
import com.fixflow.backend.identity.UserRepository;
import com.fixflow.backend.location.*;
import com.fixflow.backend.security.RequestAccess;
import java.time.Clock;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
@PreAuthorize("hasAnyRole('REPORTER', 'ADMIN', 'TECHNICIAN')")
public class MaintenanceRequestService {
    private final MaintenanceRequestRepository requests;
    private final StatusHistoryRepository history;
    private final UserRepository users;
    private final BuildingRepository buildings;
    private final RoomRepository rooms;
    private final CategoryRepository categories;
    private final RequestAccess access;
    private final Clock clock;
    public MaintenanceRequestService(MaintenanceRequestRepository requests, StatusHistoryRepository history,
                                     UserRepository users, BuildingRepository buildings, RoomRepository rooms,
                                     CategoryRepository categories, RequestAccess access, Clock clock) {
        this.requests = requests; this.history = history; this.users = users; this.buildings = buildings;
        this.rooms = rooms; this.categories = categories; this.access = access; this.clock = clock;
    }
    public RequestResponse get(long id) {
        return RequestResponse.from(requests.findOne(visible(id)).orElseThrow(ResourceNotFoundException::new));
    }
    public PageResponse<RequestResponse> list(RequestQuery query) {
        return PageResponse.from(requests.findAll(access.scope(authentication()).and(query.filter()), query.pageable())
                .map(RequestResponse::from));
    }
    public PageResponse<StatusHistoryResponse> history(long id, int page, int size) {
        if (!requests.exists(visible(id))) throw new ResourceNotFoundException();
        return PageResponse.from(history.findByRequestId(id, PageRequest.of(page, size, Sort.by("changedAt", "id")))
                .map(StatusHistoryResponse::from));
    }
    @Transactional
    @PreAuthorize("hasRole('REPORTER')")
    public RequestResponse create(RequestDetails input) {
        var reporter = users.findById(Long.valueOf(authentication().getName())).orElseThrow(ResourceNotFoundException::new);
        var request = new MaintenanceRequest(); request.setReporter(reporter); apply(request, input);
        var now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        request.setStatus(RequestStatus.OPEN); request.setPriority(Priority.MEDIUM);
        request.setCreatedAt(now); request.setUpdatedAt(now); requests.saveAndFlush(request);
        var initial = new StatusHistory(); initial.setRequest(request); initial.setChangedBy(reporter);
        initial.setNewStatus(RequestStatus.OPEN); initial.setChangedAt(now); history.saveAndFlush(initial);
        return RequestResponse.from(request);
    }
    @Transactional
    @PreAuthorize("hasRole('REPORTER')")
    public RequestResponse update(long id, RequestDetails input) {
        var request = requests.lockOwned(id, Long.valueOf(authentication().getName())).orElseThrow(ResourceNotFoundException::new);
        if (request.getStatus() != RequestStatus.OPEN)
            throw new ConflictException("Only OPEN requests may be edited by their reporter.");
        apply(request, input); requests.flush(); return RequestResponse.from(request);
    }
    private void apply(MaintenanceRequest request, RequestDetails input) {
        // Same lock order as FF-05 room writes: building before room. Category follows location.
        // Locks serialize validation with catalog deactivation until submission/update commits.
        var building = buildings.lockById(input.buildingId()).orElseThrow(ResourceNotFoundException::new);
        if (!building.isActive()) throw new ConflictException("The selected building is inactive.");
        Room room = null;
        if (input.roomId() != null) {
            room = rooms.lockById(input.roomId()).orElseThrow(ResourceNotFoundException::new);
            if (!room.getBuilding().getId().equals(building.getId()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Room must belong to the selected building");
            if (!room.isActive()) throw new ConflictException("The selected room is inactive.");
        }
        var category = categories.lockById(input.categoryId()).orElseThrow(ResourceNotFoundException::new);
        if (!category.isActive()) throw new ConflictException("The selected category is inactive.");
        request.setBuilding(building); request.setRoom(room); request.setCategory(category);
        request.setTitle(input.title()); request.setDescription(input.description()); request.setSuggestedPriority(input.suggestedPriority());
    }
    private Specification<MaintenanceRequest> visible(long id) {
        return access.scope(authentication()).and((root, query, builder) -> builder.equal(root.get("id"), id));
    }
    private org.springframework.security.core.Authentication authentication() {
        return SecurityContextHolder.getContext().getAuthentication();
    }
}
