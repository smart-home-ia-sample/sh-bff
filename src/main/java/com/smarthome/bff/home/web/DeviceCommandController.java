package com.smarthome.bff.home.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.smarthome.bff.auth.CurrentUser;
import com.smarthome.bff.home.DeviceActions;
import com.smarthome.bff.home.HomeService;
import com.smarthome.bff.home.domain.Device;
import com.smarthome.bff.mqtt.MqttGateway;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Runtime control of a device (not its record — that's {@link DeviceController}).
 * A semantic action is translated to a state change, published to the simulated
 * physical layer over MQTT, and this call blocks for the echoed state so the
 * caller (later: the Home MCP tools) gets a confirmed result.
 */
@RestController
public class DeviceCommandController {

    private final HomeService service;
    private final MqttGateway mqtt;

    public DeviceCommandController(HomeService service, MqttGateway mqtt) {
        this.service = service;
        this.mqtt = mqtt;
    }

    public record CommandRequest(@NotBlank String action, Double value) {
    }

    public record CommandResult(UUID deviceId, String nickname, String type, String roomSlug, JsonNode state) {
    }

    public record DeviceStateResult(UUID deviceId, JsonNode state) {
    }

    @PostMapping("/api/devices/{id}/command")
    public ResponseEntity<?> command(@CurrentUser String user, @PathVariable UUID id,
                                     @Valid @RequestBody CommandRequest body) {
        Device device = service.getDevice(user, id); // 404 if not owned
        // Validated against the device's announced capability descriptor, before
        // the infra, so a bad request fails fast either way:
        //   409 if the device has no descriptor yet (not provisioned)
        //   400 if the action / value isn't in the descriptor
        Map<String, Object> changes = DeviceActions.toChanges(device.getCapabilities(), body.action(), body.value());
        if (!mqtt.ready()) {
            return ResponseEntity.status(503).body(Map.of("error", "device link unavailable"));
        }
        String type = device.getType().name().toLowerCase();

        JsonNode state = mqtt.command(
                device.getHome().getId().toString(),
                device.getRoom().getSlug(),
                device.getId().toString(),
                type,
                changes);
        if (state == null) {
            return ResponseEntity.status(504).body(Map.of("error", "device did not respond in time"));
        }
        return ResponseEntity.ok(new CommandResult(
                device.getId(), device.getNickname(), type, device.getRoom().getSlug(), state));
    }

    @GetMapping("/api/devices/{id}/state")
    public DeviceStateResult state(@CurrentUser String user, @PathVariable UUID id) {
        Device device = service.getDevice(user, id);
        String key = device.getId().toString();
        JsonNode state = mqtt.currentState(key);
        if (state == null && mqtt.ready()) {
            mqtt.publishGet(device.getHome().getId().toString(), device.getRoom().getSlug(), key,
                    device.getType().name().toLowerCase());
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            state = mqtt.currentState(key);
        }
        return new DeviceStateResult(device.getId(), state);
    }
}
