package com.smarthome.bff.seed;

import com.smarthome.bff.auth.AuthProperties;
import com.smarthome.bff.home.DeviceTraitTemplates;
import com.smarthome.bff.home.domain.Device;
import com.smarthome.bff.home.domain.DeviceType;
import com.smarthome.bff.home.domain.Home;
import com.smarthome.bff.home.domain.Room;
import com.smarthome.bff.home.repo.DeviceRepository;
import com.smarthome.bff.home.repo.HomeRepository;
import com.smarthome.bff.home.repo.RoomRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Idempotent seed: on an empty device table, gives the demo user a model home
 * that is byte-for-byte the current {@code mcp/home/app/state.py::SEED_DEVICES}
 * (same rooms, same types) so nothing about the running system changes.
 */
@Component
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final AuthProperties auth;
    private final HomeRepository homes;
    private final RoomRepository rooms;
    private final DeviceRepository devices;
    private final DeviceTraitTemplates traitTemplates;

    public DataSeeder(AuthProperties auth, HomeRepository homes, RoomRepository rooms, DeviceRepository devices,
                      DeviceTraitTemplates traitTemplates) {
        this.auth = auth;
        this.homes = homes;
        this.rooms = rooms;
        this.devices = devices;
        this.traitTemplates = traitTemplates;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (devices.count() == 0) {
            try {
                seed();
            } catch (DataIntegrityViolationException raceLost) {
                // Another replica seeded first (unique constraint on homes(user_id, name)).
                log.info("seed skipped: model home already created by another instance");
            }
            return;
        }
        backfillCapabilities();
    }

    /**
     * Pre-fill {@code capabilities} for rows that predate the column (an existing
     * H2 file after {@code ddl-auto: update}), so the stack works before the
     * device-sim announces. Same template as the seed; the announce overwrites it.
     */
    private void backfillCapabilities() {
        java.util.List<Device> stale = new java.util.ArrayList<>();
        for (Device d : devices.findAll()) {
            if (d.getCapabilities() == null) {
                d.setCapabilities(traitTemplates.forType(d.getType()));
                stale.add(d);
            }
        }
        if (!stale.isEmpty()) {
            devices.saveAll(stale);
            log.info("backfilled capabilities for {} pre-existing devices", stale.size());
        }
    }

    private void seed() {
        String user = auth.demoUser();
        log.info("seeding model home for demo user '{}'", user);

        Home home = homes.save(new Home(user, "Casa Modelo", true));

        Map<String, Room> bySlug = new LinkedHashMap<>();
        bySlug.put("living_room", rooms.save(new Room(home, "Sala", "living_room")));
        bySlug.put("kitchen", rooms.save(new Room(home, "Cozinha", "kitchen")));
        bySlug.put("bedroom", rooms.save(new Room(home, "Quarto", "bedroom")));
        bySlug.put("entrance", rooms.save(new Room(home, "Entrada", "entrance")));
        bySlug.put("whole_home", rooms.save(new Room(home, "Casa toda", "whole_home")));

        // slug = the stable key the AI layer has always used for these devices
        seed(home, bySlug, "living_room", DeviceType.LIGHT, "Luz da sala", "living_room_light");
        seed(home, bySlug, "living_room", DeviceType.TV, "TV da sala", "living_room_tv");
        seed(home, bySlug, "living_room", DeviceType.CURTAIN, "Cortina da sala", "living_room_curtain");
        seed(home, bySlug, "kitchen", DeviceType.LIGHT, "Luz da cozinha", "kitchen_light");
        seed(home, bySlug, "kitchen", DeviceType.COFFEE_MAKER, "Cafeteira", "kitchen_coffee_maker");
        seed(home, bySlug, "kitchen", DeviceType.REFRIGERATOR, "Geladeira", "kitchen_refrigerator");
        seed(home, bySlug, "bedroom", DeviceType.DIMMABLE_LIGHT, "Luz do quarto", "bedroom_light");
        seed(home, bySlug, "bedroom", DeviceType.AC, "Ar-condicionado do quarto", "bedroom_ac");
        seed(home, bySlug, "bedroom", DeviceType.CURTAIN, "Cortina do quarto", "bedroom_curtain");
        seed(home, bySlug, "bedroom", DeviceType.WINDOW, "Janela do quarto", "bedroom_window");
        seed(home, bySlug, "entrance", DeviceType.DOOR, "Porta da frente", "front_door");
        seed(home, bySlug, "living_room", DeviceType.MOTION_SENSOR, "Sensor de presença", "motion_sensor");
        seed(home, bySlug, "whole_home", DeviceType.ALARM, "Alarme", "alarm");

        log.info("seed complete: 1 home, {} rooms, {} devices", rooms.count(), devices.count());
    }

    private void seed(Home home, Map<String, Room> bySlug, String roomSlug, DeviceType type,
                      String nickname, String slug) {
        Device device = new Device(home, bySlug.get(roomSlug), type, nickname, slug);
        // Pre-fill the descriptor so a fresh stack works before the device-sim
        // announces; the announce overwrites this with identical content.
        device.setCapabilities(traitTemplates.forType(type));
        devices.save(device);
    }
}
