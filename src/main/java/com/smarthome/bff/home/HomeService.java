package com.smarthome.bff.home;

import com.fasterxml.jackson.databind.JsonNode;
import com.smarthome.bff.home.domain.Device;
import com.smarthome.bff.home.domain.DeviceType;
import com.smarthome.bff.home.domain.Home;
import com.smarthome.bff.home.domain.Room;
import com.smarthome.bff.home.repo.DeviceRepository;
import com.smarthome.bff.home.repo.HomeRepository;
import com.smarthome.bff.home.repo.RoomRepository;
import com.smarthome.bff.mqtt.MqttGateway;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/** All operations are scoped to the calling user; a miss is a {@link NoSuchElementException}. */
@Service
public class HomeService {

    private final HomeRepository homes;
    private final RoomRepository rooms;
    private final DeviceRepository devices;
    private final MqttGateway mqtt;
    private final DeviceTraitTemplates traitTemplates;

    public HomeService(HomeRepository homes, RoomRepository rooms, DeviceRepository devices, MqttGateway mqtt,
                       DeviceTraitTemplates traitTemplates) {
        this.homes = homes;
        this.rooms = rooms;
        this.devices = devices;
        this.mqtt = mqtt;
        this.traitTemplates = traitTemplates;
    }

    // ---- homes -----------------------------------------------------------

    public List<Home> listHomes(String user) {
        return homes.findByUserIdOrderByCreatedAtAsc(user);
    }

    public Home getHome(String user, UUID id) {
        return homes.findByIdAndUserId(id, user)
                .orElseThrow(() -> new NoSuchElementException("home not found"));
    }

    public Home defaultHome(String user) {
        return homes.findFirstByUserIdAndIsDefaultTrue(user)
                .or(() -> homes.findByUserIdOrderByCreatedAtAsc(user).stream().findFirst())
                .orElseThrow(() -> new NoSuchElementException("no home for user"));
    }

    @Transactional
    public Home createHome(String user, String name, boolean makeDefault) {
        boolean first = !homes.existsByUserId(user);
        boolean isDefault = first || makeDefault;
        if (isDefault) {
            clearDefault(user);
        }
        return homes.save(new Home(user, name, isDefault));
    }

    @Transactional
    public Home updateHome(String user, UUID id, String name, Boolean makeDefault) {
        Home home = getHome(user, id);
        home.setName(name);
        if (Boolean.TRUE.equals(makeDefault) && !home.isDefault()) {
            clearDefault(user);
            home.setDefault(true);
        }
        return homes.save(home);
    }

    @Transactional
    public void deleteHome(String user, UUID id) {
        Home home = getHome(user, id);
        devices.deleteAll(devices.findByHomeIdOrderByNicknameAsc(id));
        rooms.deleteAll(rooms.findByHomeIdOrderByNameAsc(id));
        homes.delete(home);
        if (home.isDefault()) {
            homes.findByUserIdOrderByCreatedAtAsc(user).stream().findFirst().ifPresent(next -> {
                next.setDefault(true);
                homes.save(next);
            });
        }
    }

    private void clearDefault(String user) {
        for (Home h : homes.findByUserIdOrderByCreatedAtAsc(user)) {
            if (h.isDefault()) {
                h.setDefault(false);
                homes.save(h);
            }
        }
    }

    // ---- rooms ---------------------------------------------------------------

    public List<Room> listRooms(String user, UUID homeId) {
        getHome(user, homeId);
        return rooms.findByHomeIdOrderByNameAsc(homeId);
    }

    public Room getRoom(String user, UUID id) {
        return rooms.findByIdAndHomeUserId(id, user)
                .orElseThrow(() -> new NoSuchElementException("room not found"));
    }

    @Transactional
    public Room createRoom(String user, UUID homeId, String name, String slug) {
        Home home = getHome(user, homeId);
        if (rooms.existsByHomeIdAndSlug(homeId, slug)) {
            throw new IllegalStateException("a room with slug '" + slug + "' already exists in this home");
        }
        Room saved = rooms.save(new Room(home, name, slug));
        mqtt.publishTopologyChanged(homeId.toString());
        return saved;
    }

    @Transactional
    public Room updateRoom(String user, UUID id, String name, String slug) {
        Room room = getRoom(user, id);
        if (!room.getSlug().equals(slug) && rooms.existsByHomeIdAndSlug(room.getHome().getId(), slug)) {
            throw new IllegalStateException("a room with slug '" + slug + "' already exists in this home");
        }
        room.setName(name);
        room.setSlug(slug);
        Room saved = rooms.save(room);
        mqtt.publishTopologyChanged(room.getHome().getId().toString());
        return saved;
    }

    @Transactional
    public void deleteRoom(String user, UUID id) {
        Room room = getRoom(user, id);
        if (!devices.findByRoomIdOrderByNicknameAsc(id).isEmpty()) {
            throw new IllegalStateException("room still has devices; move or delete them first");
        }
        UUID homeId = room.getHome().getId();
        rooms.delete(room);
        mqtt.publishTopologyChanged(homeId.toString());
    }

    // ---- devices ----------------------------------------------------------

    public List<Device> listDevices(String user, UUID homeId) {
        getHome(user, homeId);
        return devices.findByHomeIdOrderByNicknameAsc(homeId);
    }

    public List<Device> listRoomDevices(String user, UUID roomId) {
        Room room = getRoom(user, roomId);
        return devices.findByRoomIdOrderByNicknameAsc(room.getId());
    }

    public Device getDevice(String user, UUID id) {
        return devices.findByIdAndHomeUserId(id, user)
                .orElseThrow(() -> new NoSuchElementException("device not found"));
    }

    @Transactional
    public Device createDevice(String user, UUID homeId, UUID roomId, DeviceType type, String nickname, String slug,
                               JsonNode capabilitiesOverride) {
        Home home = getHome(user, homeId);
        Room room = getRoom(user, roomId);
        if (!room.getHome().getId().equals(home.getId())) {
            throw new IllegalArgumentException("room does not belong to this home");
        }
        if (devices.existsByHomeIdAndNickname(homeId, nickname)) {
            throw new IllegalStateException("a device named '" + nickname + "' already exists in this home");
        }
        String finalSlug = slugOrDerive(slug, nickname);
        if (devices.existsByHomeIdAndSlug(homeId, finalSlug)) {
            throw new IllegalStateException("a device with slug '" + finalSlug + "' already exists in this home");
        }
        Device device = new Device(home, room, type, nickname, finalSlug);
        // Fallback until the device-sim announces the real descriptor over MQTT.
        device.setCapabilities(capabilitiesOverride != null ? capabilitiesOverride : traitTemplates.forType(type));
        Device saved = devices.save(device);
        mqtt.publishTopologyChanged(homeId.toString());
        return saved;
    }

    @Transactional
    public Device updateDevice(String user, UUID id, UUID roomId, DeviceType type, String nickname, String slug,
                               JsonNode capabilitiesOverride) {
        Device device = getDevice(user, id);
        Room room = getRoom(user, roomId);
        UUID homeId = device.getHome().getId();
        if (!room.getHome().getId().equals(homeId)) {
            throw new IllegalArgumentException("room does not belong to this device's home");
        }
        if (!device.getNickname().equals(nickname) && devices.existsByHomeIdAndNickname(homeId, nickname)) {
            throw new IllegalStateException("a device named '" + nickname + "' already exists in this home");
        }
        String finalSlug = slugOrDerive(slug, nickname);
        if (!device.getSlug().equals(finalSlug) && devices.existsByHomeIdAndSlug(homeId, finalSlug)) {
            throw new IllegalStateException("a device with slug '" + finalSlug + "' already exists in this home");
        }
        if (capabilitiesOverride != null) {
            device.setCapabilities(capabilitiesOverride);
        } else if (device.getType() != type) {
            // Type changed -> the old descriptor is stale; re-seed from the template
            // until the sim re-announces.
            device.setCapabilities(traitTemplates.forType(type));
        }
        device.setRoom(room);
        device.setType(type);
        device.setNickname(nickname);
        device.setSlug(finalSlug);
        Device saved = devices.save(device);
        mqtt.publishTopologyChanged(homeId.toString());
        return saved;
    }

    private static String slugOrDerive(String slug, String nickname) {
        String source = (slug == null || slug.isBlank()) ? nickname : slug;
        String noAccents = Normalizer.normalize(source, Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
        String s = noAccents.toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("(^_|_$)", "");
        return s.isBlank() ? "device" : s;
    }

    @Transactional
    public void deleteDevice(String user, UUID id) {
        Device device = getDevice(user, id);
        UUID homeId = device.getHome().getId();
        devices.delete(device);
        mqtt.publishTopologyChanged(homeId.toString());
    }
}
