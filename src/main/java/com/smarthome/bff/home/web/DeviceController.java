package com.smarthome.bff.home.web;

import com.smarthome.bff.auth.CurrentUser;
import com.smarthome.bff.home.HomeService;
import com.smarthome.bff.home.web.Dtos.CreateDeviceRequest;
import com.smarthome.bff.home.web.Dtos.DeviceDto;
import com.smarthome.bff.home.web.Dtos.UpdateDeviceRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class DeviceController {

    private final HomeService service;

    public DeviceController(HomeService service) {
        this.service = service;
    }

    @GetMapping("/api/homes/{homeId}/devices")
    public List<DeviceDto> listByHome(@CurrentUser String user, @PathVariable UUID homeId) {
        return service.listDevices(user, homeId).stream().map(DeviceDto::of).toList();
    }

    @GetMapping("/api/rooms/{roomId}/devices")
    public List<DeviceDto> listByRoom(@CurrentUser String user, @PathVariable UUID roomId) {
        return service.listRoomDevices(user, roomId).stream().map(DeviceDto::of).toList();
    }

    @PostMapping("/api/homes/{homeId}/devices")
    @ResponseStatus(HttpStatus.CREATED)
    public DeviceDto create(@CurrentUser String user, @PathVariable UUID homeId,
                            @Valid @RequestBody CreateDeviceRequest body) {
        return DeviceDto.of(service.createDevice(
                user, homeId, body.roomId(), body.type(), body.nickname(), body.slug(), body.capabilities()));
    }

    @GetMapping("/api/devices/{id}")
    public DeviceDto get(@CurrentUser String user, @PathVariable UUID id) {
        return DeviceDto.of(service.getDevice(user, id));
    }

    @PutMapping("/api/devices/{id}")
    public DeviceDto update(@CurrentUser String user, @PathVariable UUID id,
                            @Valid @RequestBody UpdateDeviceRequest body) {
        return DeviceDto.of(service.updateDevice(
                user, id, body.roomId(), body.type(), body.nickname(), body.slug(), body.capabilities()));
    }

    @DeleteMapping("/api/devices/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUser String user, @PathVariable UUID id) {
        service.deleteDevice(user, id);
    }
}
