package com.fixflow.backend.location;

import com.fixflow.backend.common.api.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@PreAuthorize("hasAnyRole('ADMIN', 'REPORTER', 'TECHNICIAN')")
public class BuildingService {
    private final BuildingRepository buildings;
    public BuildingService(BuildingRepository buildings) { this.buildings = buildings; }
    public BuildingResponse get(long id) {
        var building = buildings.findById(id).orElseThrow(ResourceNotFoundException::new);
        if (!building.isActive() && !CatalogSupport.isAdmin()) throw new ResourceNotFoundException();
        return BuildingResponse.from(building);
    }
    public PageResponse<BuildingResponse> list(CatalogQuery query) {
        return PageResponse.from(buildings.findAll(CatalogSupport.filter(query), query.pageable()).map(BuildingResponse::from));
    }
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public BuildingResponse create(BuildingRequest input) {
        var building = new Building(); apply(building, input);
        return BuildingResponse.from(buildings.saveAndFlush(building));
    }
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public BuildingResponse update(long id, BuildingRequest input) {
        var building = buildings.lockById(id).orElseThrow(ResourceNotFoundException::new);
        apply(building, input); buildings.flush();
        return BuildingResponse.from(building);
    }
    private void apply(Building building, BuildingRequest input) {
        building.setCode(CatalogSupport.code(input.code())); building.setName(input.name().strip());
        building.setDescription(CatalogSupport.optionalText(input.description())); building.setActive(input.active());
    }
}
