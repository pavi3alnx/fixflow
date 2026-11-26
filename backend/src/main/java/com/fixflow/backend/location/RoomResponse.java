package com.fixflow.backend.location;

public record RoomResponse(Long id, Long buildingId, String code, String name, String description,
                           boolean active, boolean available) {
    static RoomResponse from(Room room) {
        return new RoomResponse(room.getId(), room.getBuilding().getId(), room.getCode(), room.getName(),
                room.getDescription(), room.isActive(), room.isActive() && room.getBuilding().isActive());
    }
}
