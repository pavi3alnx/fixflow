package com.fixflow.backend.request;

import java.time.Instant;

public record RequestResponse(Long id, Long reporterId, String title, String description,
                              Reference building, Reference room, Reference category,
                              Priority suggestedPriority, Priority priority, RequestStatus status,
                              Instant createdAt, Instant updatedAt) {
    public record Reference(Long id, String code, String name, boolean active) { }
    static RequestResponse from(MaintenanceRequest request) {
        var building = request.getBuilding(); var room = request.getRoom(); var category = request.getCategory();
        return new RequestResponse(request.getId(), request.getReporter().getId(), request.getTitle(), request.getDescription(),
                new Reference(building.getId(), building.getCode(), building.getName(), building.isActive()),
                room == null ? null : new Reference(room.getId(), room.getCode(), room.getName(), room.isActive()),
                new Reference(category.getId(), category.getCode(), category.getName(), category.isActive()),
                request.getSuggestedPriority(), request.getPriority(), request.getStatus(), request.getCreatedAt(), request.getUpdatedAt());
    }
}
