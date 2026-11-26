package com.fixflow.backend.location;

import com.fixflow.backend.common.api.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@PreAuthorize("hasAnyRole('ADMIN', 'REPORTER', 'TECHNICIAN')")
public class RoomService {
    private final RoomRepository rooms;
    private final BuildingRepository buildings;
    public RoomService(RoomRepository rooms, BuildingRepository buildings) {
        this.rooms = rooms; this.buildings = buildings;
    }
    public RoomResponse get(long id) {
        var room = rooms.findById(id).orElseThrow(ResourceNotFoundException::new);
        if ((!room.isActive() || !room.getBuilding().isActive()) && !CatalogSupport.isAdmin())
            throw new ResourceNotFoundException();
        return RoomResponse.from(room);
    }
    public PageResponse<RoomResponse> list(CatalogQuery query, Long buildingId) {
        boolean admin = CatalogSupport.isAdmin();
        if (buildingId != null) {
            var building = buildings.findById(buildingId).orElseThrow(ResourceNotFoundException::new);
            if (!building.isActive() && !admin) throw new ResourceNotFoundException();
        }
        Specification<Room> specification = CatalogSupport.filter(query);
        if (buildingId != null) specification = specification.and((root, criteria, builder) ->
                builder.equal(root.get("building").get("id"), buildingId));
        if (!admin) specification = specification.and((root, criteria, builder) ->
                builder.isTrue(root.get("building").get("active")));
        return PageResponse.from(rooms.findAll(specification, query.pageable()).map(RoomResponse::from));
    }
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public RoomResponse create(RoomRequest input) {
        var building = buildings.lockById(input.buildingId()).orElseThrow(ResourceNotFoundException::new);
        if (!building.isActive()) throw new ConflictException("Rooms cannot be created under an inactive building.");
        var room = new Room(); room.setBuilding(building); apply(room, input);
        return RoomResponse.from(rooms.saveAndFlush(room));
    }
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public RoomResponse update(long id, RoomRequest input) {
        // Parent is immutable. Lock parent before child, consistently with room creation.
        var building = buildings.lockById(input.buildingId()).orElseThrow(ResourceNotFoundException::new);
        var room = rooms.lockById(id).orElseThrow(ResourceNotFoundException::new);
        if (!room.getBuilding().getId().equals(building.getId()))
            throw new ConflictException("A room's building cannot be changed. Deactivate it and create another room.");
        if (input.active() && !building.isActive())
            throw new ConflictException("A room cannot be activated while its building is inactive.");
        apply(room, input); rooms.flush(); return RoomResponse.from(room);
    }
    private void apply(Room room, RoomRequest input) {
        room.setCode(CatalogSupport.code(input.code())); room.setName(CatalogSupport.optionalText(input.name()));
        room.setDescription(CatalogSupport.optionalText(input.description())); room.setActive(input.active());
    }
}
