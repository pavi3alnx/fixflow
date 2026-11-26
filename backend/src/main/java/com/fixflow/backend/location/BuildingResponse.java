package com.fixflow.backend.location;

public record BuildingResponse(Long id, String code, String name, String description, boolean active) {
    static BuildingResponse from(Building building) {
        return new BuildingResponse(building.getId(), building.getCode(), building.getName(),
                building.getDescription(), building.isActive());
    }
}
