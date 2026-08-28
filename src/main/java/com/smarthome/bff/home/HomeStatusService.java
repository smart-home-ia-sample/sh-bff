package com.smarthome.bff.home;

import com.fasterxml.jackson.databind.JsonNode;
import com.smarthome.bff.home.domain.Device;
import com.smarthome.bff.home.domain.DeviceType;
import com.smarthome.bff.home.domain.Home;
import com.smarthome.bff.home.domain.Room;
import com.smarthome.bff.mqtt.MqttGateway;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the Dashboard snapshot: the user's rooms and devices (topology from H2)
 * joined with each device's latest state (from the MQTT read-model), plus a few
 * trivially-derived rollups. Deep energy analysis is the Energy agent's job on
 * the assistant path, not here.
 */
@Service
public class HomeStatusService {

    private static final Map<DeviceType, Integer> WATTS_WHEN_ON = Map.of(
            DeviceType.LIGHT, 10,
            DeviceType.DIMMABLE_LIGHT, 10,
            DeviceType.TV, 120,
            DeviceType.AC, 1500,
            DeviceType.COFFEE_MAKER, 900,
            DeviceType.REFRIGERATOR, 150);

    private final HomeService homeService;
    private final MqttGateway mqtt;

    public HomeStatusService(HomeService homeService, MqttGateway mqtt) {
        this.homeService = homeService;
        this.mqtt = mqtt;
    }

    public record DeviceStatus(UUID deviceId, String slug, String nickname, String type,
                               JsonNode capabilities, JsonNode state) {
    }

    public record RoomStatus(String slug, String name, List<DeviceStatus> devices) {
    }

    public record Rollups(boolean alarmArmed, boolean allDoorsLocked, int openDoors,
                          int totalWatts, int activeDevices) {
    }

    public record EventEntry(UUID deviceId, String slug, String nickname, String type, JsonNode state, long at) {
    }

    public record Snapshot(boolean simulatorOnline, List<RoomStatus> rooms, Rollups rollups,
                           List<EventEntry> events) {
    }

    /** Sent on every device state change instead of the whole snapshot. */
    public record Delta(UUID deviceId, String slug, String nickname, String type, String roomSlug,
                        JsonNode state, long at, Rollups rollups) {
    }

    @Transactional(readOnly = true)
    public Snapshot forUser(String user) {
        Home home = homeService.defaultHome(user);
        List<Room> rooms = homeService.listRooms(user, home.getId());
        List<Device> devices = homeService.listDevices(user, home.getId());

        Map<UUID, Device> byId = new LinkedHashMap<>();
        for (Device d : devices) {
            byId.put(d.getId(), d);
        }

        Map<String, List<DeviceStatus>> byRoomSlug = new LinkedHashMap<>();
        for (Room r : rooms) {
            byRoomSlug.put(r.getSlug(), new ArrayList<>());
        }

        for (Device d : devices) {
            String key = d.getId().toString();
            String type = d.getType().name().toLowerCase();
            JsonNode state = mqtt.currentState(key);
            byRoomSlug.computeIfAbsent(d.getRoom().getSlug(), k -> new ArrayList<>())
                    .add(new DeviceStatus(d.getId(), d.getSlug(), d.getNickname(), type, d.getCapabilities(), state));

            if (state == null && mqtt.ready()) {
                // lazy hydration: ask the simulator so the next update has it.
                mqtt.publishGet(home.getId().toString(), d.getRoom().getSlug(), key, type);
            }
        }

        List<RoomStatus> roomStatuses = new ArrayList<>();
        for (Room r : rooms) {
            roomStatuses.add(new RoomStatus(r.getSlug(), r.getName(), byRoomSlug.get(r.getSlug())));
        }

        // newest first, so it matches the order the front prepends deltas in
        List<EventEntry> events = new ArrayList<>();
        List<MqttGateway.StateChange> changes = mqtt.recentChanges();
        for (int i = changes.size() - 1; i >= 0 && events.size() < 10; i--) {
            MqttGateway.StateChange c = changes.get(i);
            Device d = byId.get(safeUuid(c.deviceId()));
            if (d != null) {
                events.add(new EventEntry(d.getId(), d.getSlug(), d.getNickname(),
                        d.getType().name().toLowerCase(), c.state(), c.at()));
            }
        }

        return new Snapshot(mqtt.simulatorOnline(), roomStatuses, computeRollups(devices), events);
    }

    /** The delta for one device that just changed, or null if it isn't the user's. */
    @Transactional(readOnly = true)
    public Delta deltaForDevice(String user, String deviceId, long at) {
        Home home = homeService.defaultHome(user);
        List<Device> devices = homeService.listDevices(user, home.getId());
        Device device = devices.stream()
                .filter(d -> d.getId().toString().equals(deviceId))
                .findFirst()
                .orElse(null);
        if (device == null) {
            return null;
        }
        return new Delta(
                device.getId(),
                device.getSlug(),
                device.getNickname(),
                device.getType().name().toLowerCase(),
                device.getRoom().getSlug(),
                mqtt.currentState(deviceId),
                at,
                computeRollups(devices));
    }

    private Rollups computeRollups(List<Device> devices) {
        boolean alarmArmed = false;
        int doors = 0, doorsLocked = 0, openDoors = 0, totalWatts = 0, activeDevices = 0;

        for (Device d : devices) {
            JsonNode state = mqtt.currentState(d.getId().toString());
            if (state == null) {
                continue;
            }
            switch (d.getType()) {
                case ALARM -> alarmArmed = state.path("armed").asBoolean(false);
                case DOOR -> {
                    doors++;
                    if (state.path("locked").asBoolean(false)) doorsLocked++;
                    if (state.path("open").asBoolean(false)) openDoors++;
                }
                default -> {
                }
            }
            if (state.path("on").asBoolean(false)) {
                activeDevices++;
                Integer w = WATTS_WHEN_ON.get(d.getType());
                if (w != null) totalWatts += w;
            } else if (state.path("open").asBoolean(false) || state.path("armed").asBoolean(false)) {
                activeDevices++;
            }
        }
        return new Rollups(alarmArmed, doors > 0 && doorsLocked == doors, openDoors, totalWatts, activeDevices);
    }

    private static UUID safeUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
