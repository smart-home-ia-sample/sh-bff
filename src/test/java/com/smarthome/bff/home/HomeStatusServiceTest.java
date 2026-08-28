package com.smarthome.bff.home;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarthome.bff.home.domain.Device;
import com.smarthome.bff.home.domain.DeviceType;
import com.smarthome.bff.home.domain.Home;
import com.smarthome.bff.home.domain.Room;
import com.smarthome.bff.mqtt.MqttGateway;
import com.smarthome.bff.mqtt.MqttGateway.StateChange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The snapshot / delta / rollup logic, exercised with a faked MQTT read-model. */
@ExtendWith(MockitoExtension.class)
class HomeStatusServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock
    HomeService homeService;
    @Mock
    MqttGateway mqtt;

    HomeStatusService service;

    private final UUID homeId = UUID.randomUUID();
    private Home home;
    private Room sala;
    private Room quarto;
    private Device light;   // sala, on
    private Device door;    // sala, locked, closed
    private Device ac;      // quarto, on
    private Device alarm;   // quarto, armed
    private Device tv;      // quarto, no state yet

    private static JsonNode node(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Device device(Room room, DeviceType type, String nickname, String slug) {
        Device d = new Device(home, room, type, nickname, slug);
        ReflectionTestUtils.setField(d, "id", UUID.randomUUID());
        return d;
    }

    @BeforeEach
    void setUp() {
        service = new HomeStatusService(homeService, mqtt);
        home = new Home("demo", "Casa", true);
        ReflectionTestUtils.setField(home, "id", homeId);
        sala = new Room(home, "Sala", "sala");
        quarto = new Room(home, "Quarto", "quarto");

        light = device(sala, DeviceType.LIGHT, "Luz", "luz");
        door = device(sala, DeviceType.DOOR, "Porta", "porta");
        ac = device(quarto, DeviceType.AC, "Ar", "ar");
        alarm = device(quarto, DeviceType.ALARM, "Alarme", "alarme");
        tv = device(quarto, DeviceType.TV, "TV", "tv");

        lenient().when(homeService.defaultHome("demo")).thenReturn(home);
        lenient().when(homeService.listRooms("demo", homeId)).thenReturn(List.of(sala, quarto));
        lenient().when(homeService.listDevices("demo", homeId))
                .thenReturn(List.of(light, door, ac, alarm, tv));

        lenient().when(mqtt.currentState(light.getId().toString())).thenReturn(node("{\"on\":true}"));
        lenient().when(mqtt.currentState(door.getId().toString())).thenReturn(node("{\"locked\":true,\"open\":false}"));
        lenient().when(mqtt.currentState(ac.getId().toString())).thenReturn(node("{\"on\":true,\"temperature\":21}"));
        lenient().when(mqtt.currentState(alarm.getId().toString())).thenReturn(node("{\"armed\":true}"));
        lenient().when(mqtt.currentState(tv.getId().toString())).thenReturn(null);
        lenient().when(mqtt.recentChanges()).thenReturn(List.of());
        lenient().when(mqtt.simulatorOnline()).thenReturn(true);
    }

    @Test
    void snapshotGroupsDevicesByRoomAndJoinsState() {
        HomeStatusService.Snapshot snap = service.forUser("demo");

        assertThat(snap.simulatorOnline()).isTrue();
        assertThat(snap.rooms()).extracting(HomeStatusService.RoomStatus::slug).containsExactly("sala", "quarto");
        assertThat(snap.rooms().get(0).devices()).extracting(HomeStatusService.DeviceStatus::nickname)
                .containsExactly("Luz", "Porta");
        assertThat(snap.rooms().get(0).devices().get(0).state().path("on").asBoolean()).isTrue();
        assertThat(snap.rooms().get(1).devices().get(2).state()).isNull(); // tv
    }

    @Test
    void rollupsAggregateAcrossDevices() {
        HomeStatusService.Rollups r = service.forUser("demo").rollups();

        assertThat(r.alarmArmed()).isTrue();
        assertThat(r.allDoorsLocked()).isTrue();
        assertThat(r.openDoors()).isZero();
        assertThat(r.totalWatts()).isEqualTo(1510); // light 10 + ac 1500
        assertThat(r.activeDevices()).isEqualTo(3); // light on, ac on, alarm armed
    }

    @Test
    void anUnlockedOpenDoorFlipsTheLockAndOpenRollups() {
        when(mqtt.currentState(door.getId().toString())).thenReturn(node("{\"locked\":false,\"open\":true}"));

        HomeStatusService.Rollups r = service.forUser("demo").rollups();

        assertThat(r.allDoorsLocked()).isFalse();
        assertThat(r.openDoors()).isEqualTo(1);
        assertThat(r.activeDevices()).isEqualTo(4); // + the open door
    }

    @Test
    void aDeviceWithNoStateYetTriggersLazyHydrationWhenTheLinkIsUp() {
        when(mqtt.ready()).thenReturn(true);

        service.forUser("demo");

        verify(mqtt).publishGet(eq(homeId.toString()), eq("quarto"), eq(tv.getId().toString()), eq("tv"));
    }

    @Test
    void noLazyHydrationWhenTheLinkIsDown() {
        when(mqtt.ready()).thenReturn(false);

        service.forUser("demo");

        verify(mqtt, never()).publishGet(any(), any(), any(), any());
    }

    @Test
    void eventFeedIsNewestFirstAndSkipsForeignOrUnparseableChanges() {
        when(mqtt.recentChanges()).thenReturn(List.of(
                new StateChange(light.getId().toString(), node("{\"on\":false}"), 100L),
                new StateChange("not-a-uuid", node("{}"), 200L),
                new StateChange(UUID.randomUUID().toString(), node("{}"), 300L)));

        List<HomeStatusService.EventEntry> events = service.forUser("demo").events();

        assertThat(events).hasSize(1);
        assertThat(events.get(0).nickname()).isEqualTo("Luz");
        assertThat(events.get(0).at()).isEqualTo(100L);
    }

    @Test
    void eventFeedIsCappedAtTen() {
        List<StateChange> many = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            many.add(new StateChange(light.getId().toString(), node("{\"on\":true}"), i));
        }
        when(mqtt.recentChanges()).thenReturn(many);

        assertThat(service.forUser("demo").events()).hasSize(10);
    }

    @Test
    void deltaForOwnedDeviceCarriesStateAndRollups() {
        HomeStatusService.Delta delta = service.deltaForDevice("demo", ac.getId().toString(), 999L);

        assertThat(delta).isNotNull();
        assertThat(delta.roomSlug()).isEqualTo("quarto");
        assertThat(delta.type()).isEqualTo("ac");
        assertThat(delta.at()).isEqualTo(999L);
        assertThat(delta.state().path("temperature").asInt()).isEqualTo(21);
        assertThat(delta.rollups().totalWatts()).isEqualTo(1510);
    }

    @Test
    void deltaForADeviceThatIsNotThisUsersIsNull() {
        assertThat(service.deltaForDevice("demo", UUID.randomUUID().toString(), 1L)).isNull();
    }
}
