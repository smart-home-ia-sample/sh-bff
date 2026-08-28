package com.smarthome.bff.home.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.smarthome.bff.home.domain.Device;
import com.smarthome.bff.home.domain.DeviceType;
import com.smarthome.bff.home.domain.Home;
import com.smarthome.bff.home.domain.Room;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

/** Request and response shapes for the home/room/device CRUD API. */
public final class Dtos {

    private Dtos() {
    }

    public record HomeDto(UUID id, String name, boolean isDefault) {
        public static HomeDto of(Home h) {
            return new HomeDto(h.getId(), h.getName(), h.isDefault());
        }
    }

    public record RoomDto(UUID id, UUID homeId, String name, String slug) {
        public static RoomDto of(Room r) {
            return new RoomDto(r.getId(), r.getHome().getId(), r.getName(), r.getSlug());
        }
    }

    public record DeviceDto(UUID id, UUID homeId, UUID roomId, String roomSlug,
                            String type, String nickname, String slug, JsonNode capabilities, Instant createdAt) {
        public static DeviceDto of(Device d) {
            return new DeviceDto(d.getId(), d.getHome().getId(), d.getRoom().getId(),
                    d.getRoom().getSlug(), d.getType().name(), d.getNickname(), d.getSlug(),
                    d.getCapabilities(), d.getCreatedAt());
        }
    }

    public record CreateHomeRequest(@NotBlank String name, Boolean isDefault) {
    }

    public record UpdateHomeRequest(@NotBlank String name, Boolean isDefault) {
    }

    public record CreateRoomRequest(@NotBlank String name, @NotBlank String slug) {
    }

    public record UpdateRoomRequest(@NotBlank String name, @NotBlank String slug) {
    }

    // `capabilities` is an optional descriptor override — a real controller can
    // force-set one; normally omitted and filled by the device's own announce
    // (or the seed/type template as a fallback).
    public record CreateDeviceRequest(@NotNull UUID roomId, @NotNull DeviceType type, @NotBlank String nickname,
                                      String slug, JsonNode capabilities) {
    }

    public record UpdateDeviceRequest(@NotNull UUID roomId, @NotNull DeviceType type, @NotBlank String nickname,
                                      String slug, JsonNode capabilities) {
    }
}
