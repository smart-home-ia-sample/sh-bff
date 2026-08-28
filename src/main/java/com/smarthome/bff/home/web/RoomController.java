package com.smarthome.bff.home.web;

import com.smarthome.bff.auth.CurrentUser;
import com.smarthome.bff.home.HomeService;
import com.smarthome.bff.home.web.Dtos.CreateRoomRequest;
import com.smarthome.bff.home.web.Dtos.RoomDto;
import com.smarthome.bff.home.web.Dtos.UpdateRoomRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.List;
import java.util.UUID;

@RestController
public class RoomController {

    private final HomeService service;

    public RoomController(HomeService service) {
        this.service = service;
    }

    @GetMapping("/api/homes/{homeId}/rooms")
    public List<RoomDto> list(@CurrentUser String user, @PathVariable UUID homeId) {
        return service.listRooms(user, homeId).stream().map(RoomDto::of).toList();
    }

    @PostMapping("/api/homes/{homeId}/rooms")
    @ResponseStatus(HttpStatus.CREATED)
    public RoomDto create(@CurrentUser String user, @PathVariable UUID homeId,
                          @Valid @RequestBody CreateRoomRequest body) {
        return RoomDto.of(service.createRoom(user, homeId, body.name(), body.slug()));
    }

    @GetMapping("/api/rooms/{id}")
    public RoomDto get(@CurrentUser String user, @PathVariable UUID id) {
        return RoomDto.of(service.getRoom(user, id));
    }

    @PutMapping("/api/rooms/{id}")
    public RoomDto update(@CurrentUser String user, @PathVariable UUID id,
                          @Valid @RequestBody UpdateRoomRequest body) {
        return RoomDto.of(service.updateRoom(user, id, body.name(), body.slug()));
    }

    @DeleteMapping("/api/rooms/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUser String user, @PathVariable UUID id) {
        service.deleteRoom(user, id);
    }
}
