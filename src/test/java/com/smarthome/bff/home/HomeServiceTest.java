package com.smarthome.bff.home;

import com.smarthome.bff.home.domain.Device;
import com.smarthome.bff.home.domain.DeviceType;
import com.smarthome.bff.home.domain.Home;
import com.smarthome.bff.home.domain.Room;
import com.smarthome.bff.home.repo.DeviceRepository;
import com.smarthome.bff.home.repo.HomeRepository;
import com.smarthome.bff.home.repo.RoomRepository;
import com.smarthome.bff.mqtt.MqttGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Branch logic of {@link HomeService} that is tedious to reach over HTTP. */
@ExtendWith(MockitoExtension.class)
class HomeServiceTest {

    @Mock
    HomeRepository homes;
    @Mock
    RoomRepository rooms;
    @Mock
    DeviceRepository devices;
    @Mock
    MqttGateway mqtt;
    @Mock
    DeviceTraitTemplates traitTemplates;
    @InjectMocks
    HomeService service;

    private Home home;
    private final UUID homeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        home = new Home("demo", "Casa", true);
        ReflectionTestUtils.setField(home, "id", homeId);
        lenient().when(homes.save(any(Home.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(rooms.save(any(Room.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(devices.save(any(Device.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void gettingSomeoneElsesHomeIsNotFound() {
        when(homes.findByIdAndUserId(homeId, "demo")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getHome("demo", homeId))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void firstHomeIsForcedDefaultEvenWhenNotRequested() {
        when(homes.existsByUserId("demo")).thenReturn(false);
        when(homes.findByUserIdOrderByCreatedAtAsc("demo")).thenReturn(List.of());

        Home created = service.createHome("demo", "Primeira", false);

        assertThat(created.isDefault()).isTrue();
    }

    @Test
    void makingAHomeDefaultClearsThePreviousDefault() {
        Home previous = new Home("demo", "Antiga", true);
        Home target = new Home("demo", "Nova", false);
        when(homes.existsByUserId("demo")).thenReturn(true);
        when(homes.findByUserIdOrderByCreatedAtAsc("demo")).thenReturn(List.of(previous, target));

        service.createHome("demo", "Nova", true);

        assertThat(previous.isDefault()).isFalse();
    }

    @Test
    void creatingARoomWithADuplicateSlugIsRejected() {
        when(homes.findByIdAndUserId(homeId, "demo")).thenReturn(Optional.of(home));
        when(rooms.existsByHomeIdAndSlug(homeId, "sala")).thenReturn(true);

        assertThatThrownBy(() -> service.createRoom("demo", homeId, "Sala", "sala"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("slug 'sala'");
        verify(rooms, never()).save(any());
    }

    @Test
    void deletingARoomThatStillHasDevicesIsRejected() {
        UUID roomId = UUID.randomUUID();
        Room room = new Room(home, "Sala", "sala");
        when(rooms.findByIdAndHomeUserId(roomId, "demo")).thenReturn(Optional.of(room));
        when(devices.findByRoomIdOrderByNicknameAsc(roomId))
                .thenReturn(List.of(new Device(home, room, DeviceType.LIGHT, "Luz", "luz")));

        assertThatThrownBy(() -> service.deleteRoom("demo", roomId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("still has devices");
        verify(rooms, never()).delete(any());
    }

    @Test
    void creatingADeviceInARoomFromAnotherHomeIsRejected() {
        UUID roomId = UUID.randomUUID();
        Home otherHome = new Home("demo", "Outra", false);
        ReflectionTestUtils.setField(otherHome, "id", UUID.randomUUID());
        Room foreignRoom = new Room(otherHome, "Sala", "sala");
        when(homes.findByIdAndUserId(homeId, "demo")).thenReturn(Optional.of(home));
        when(rooms.findByIdAndHomeUserId(roomId, "demo")).thenReturn(Optional.of(foreignRoom));

        assertThatThrownBy(() -> service.createDevice("demo", homeId, roomId, DeviceType.LIGHT, "Luz", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(devices, never()).save(any());
    }

    @Test
    void duplicateNicknameOnCreateIsRejected() {
        UUID roomId = UUID.randomUUID();
        Room room = new Room(home, "Sala", "sala");
        when(homes.findByIdAndUserId(homeId, "demo")).thenReturn(Optional.of(home));
        when(rooms.findByIdAndHomeUserId(roomId, "demo")).thenReturn(Optional.of(room));
        when(devices.existsByHomeIdAndNickname(homeId, "Luz da sala")).thenReturn(true);

        assertThatThrownBy(() -> service.createDevice("demo", homeId, roomId, DeviceType.LIGHT, "Luz da sala", null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Luz da sala");
    }

    @Test
    void renamingADeviceToItsOwnNicknameIsAllowed() {
        UUID deviceId = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        Room room = new Room(home, "Sala", "sala");
        Device device = new Device(home, room, DeviceType.LIGHT, "Luz", "luz");
        when(devices.findByIdAndHomeUserId(deviceId, "demo")).thenReturn(Optional.of(device));
        when(rooms.findByIdAndHomeUserId(roomId, "demo")).thenReturn(Optional.of(room));

        Device updated = service.updateDevice("demo", deviceId, roomId, DeviceType.CURTAIN, "Luz", null, null);

        assertThat(updated.getType()).isEqualTo(DeviceType.CURTAIN);
        verify(devices, never()).existsByHomeIdAndNickname(any(), any());
    }

    // ---- defaultHome fallbacks --------------------------------------------

    @Test
    void defaultHomeFallsBackToTheOldestWhenNoneIsFlaggedDefault() {
        Home oldest = new Home("demo", "A", false);
        when(homes.findFirstByUserIdAndIsDefaultTrue("demo")).thenReturn(Optional.empty());
        when(homes.findByUserIdOrderByCreatedAtAsc("demo")).thenReturn(List.of(oldest, new Home("demo", "B", false)));

        assertThat(service.defaultHome("demo")).isSameAs(oldest);
    }

    @Test
    void defaultHomeThrowsWhenTheUserHasNone() {
        when(homes.findFirstByUserIdAndIsDefaultTrue("demo")).thenReturn(Optional.empty());
        when(homes.findByUserIdOrderByCreatedAtAsc("demo")).thenReturn(List.of());

        assertThatThrownBy(() -> service.defaultHome("demo")).isInstanceOf(NoSuchElementException.class);
    }

    // ---- update / delete home -------------------------------------------

    @Test
    void updatingAHomeToDefaultClearsThePreviousDefault() {
        UUID id = UUID.randomUUID();
        Home target = new Home("demo", "Nova", false);
        Home previous = new Home("demo", "Antiga", true);
        when(homes.findByIdAndUserId(id, "demo")).thenReturn(Optional.of(target));
        when(homes.findByUserIdOrderByCreatedAtAsc("demo")).thenReturn(List.of(previous, target));

        Home updated = service.updateHome("demo", id, "Nova!", true);

        assertThat(updated.getName()).isEqualTo("Nova!");
        assertThat(updated.isDefault()).isTrue();
        assertThat(previous.isDefault()).isFalse();
    }

    @Test
    void deletingTheDefaultHomePromotesTheNextOne() {
        UUID id = UUID.randomUUID();
        Home doomed = new Home("demo", "Casa", true);
        ReflectionTestUtils.setField(doomed, "id", id);
        Home next = new Home("demo", "Sítio", false);
        when(homes.findByIdAndUserId(id, "demo")).thenReturn(Optional.of(doomed));
        when(devices.findByHomeIdOrderByNicknameAsc(id)).thenReturn(List.of());
        when(rooms.findByHomeIdOrderByNameAsc(id)).thenReturn(List.of());
        when(homes.findByUserIdOrderByCreatedAtAsc("demo")).thenReturn(List.of(next));

        service.deleteHome("demo", id);

        assertThat(next.isDefault()).isTrue();
        verify(homes).delete(doomed);
    }

    // ---- rooms ---------------------------------------------------------------

    @Test
    void creatingARoomPublishesATopologyChange() {
        when(homes.findByIdAndUserId(homeId, "demo")).thenReturn(Optional.of(home));
        when(rooms.existsByHomeIdAndSlug(homeId, "cozinha")).thenReturn(false);

        service.createRoom("demo", homeId, "Cozinha", "cozinha");

        verify(rooms).save(any(Room.class));
        verify(mqtt).publishTopologyChanged(homeId.toString());
    }

    @Test
    void changingARoomSlugToAnExistingOneIsRejected() {
        UUID roomId = UUID.randomUUID();
        Room room = new Room(home, "Sala", "sala");
        when(rooms.findByIdAndHomeUserId(roomId, "demo")).thenReturn(Optional.of(room));
        when(rooms.existsByHomeIdAndSlug(homeId, "cozinha")).thenReturn(true);

        assertThatThrownBy(() -> service.updateRoom("demo", roomId, "Sala", "cozinha"))
                .isInstanceOf(IllegalStateException.class);
        verify(mqtt, never()).publishTopologyChanged(any());
    }

    @Test
    void keepingARoomSlugSkipsTheDuplicateCheck() {
        UUID roomId = UUID.randomUUID();
        Room room = new Room(home, "Sala", "sala");
        when(rooms.findByIdAndHomeUserId(roomId, "demo")).thenReturn(Optional.of(room));

        service.updateRoom("demo", roomId, "Sala de estar", "sala");

        assertThat(room.getName()).isEqualTo("Sala de estar");
        verify(rooms, never()).existsByHomeIdAndSlug(any(), any());
        verify(mqtt).publishTopologyChanged(homeId.toString());
    }

    // ---- devices ----------------------------------------------------------

    @Test
    void creatingADeviceDerivesTheSlugAndSeedsCapabilitiesFromTheTemplate() {
        UUID roomId = UUID.randomUUID();
        Room room = new Room(home, "Sala", "sala");
        when(homes.findByIdAndUserId(homeId, "demo")).thenReturn(Optional.of(room.getHome()));
        when(rooms.findByIdAndHomeUserId(roomId, "demo")).thenReturn(Optional.of(room));

        Device created = service.createDevice("demo", homeId, roomId, DeviceType.LIGHT, "Luz da Área", null, null);

        assertThat(created.getSlug()).isEqualTo("luz_da_area");
        verify(traitTemplates).forType(DeviceType.LIGHT);
        verify(mqtt).publishTopologyChanged(homeId.toString());
    }

    @Test
    void aBlankDerivedSlugFallsBackToDevice() {
        UUID roomId = UUID.randomUUID();
        Room room = new Room(home, "Sala", "sala");
        when(homes.findByIdAndUserId(homeId, "demo")).thenReturn(Optional.of(room.getHome()));
        when(rooms.findByIdAndHomeUserId(roomId, "demo")).thenReturn(Optional.of(room));

        Device created = service.createDevice("demo", homeId, roomId, DeviceType.LIGHT, "!!!", null, null);

        assertThat(created.getSlug()).isEqualTo("device");
    }

    @Test
    void aDuplicateSlugOnCreateIsRejected() {
        UUID roomId = UUID.randomUUID();
        Room room = new Room(home, "Sala", "sala");
        when(homes.findByIdAndUserId(homeId, "demo")).thenReturn(Optional.of(room.getHome()));
        when(rooms.findByIdAndHomeUserId(roomId, "demo")).thenReturn(Optional.of(room));
        when(devices.existsByHomeIdAndSlug(homeId, "luz")).thenReturn(true);

        assertThatThrownBy(() -> service.createDevice("demo", homeId, roomId, DeviceType.LIGHT, "Luz", "luz", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("slug 'luz'");
    }

    @Test
    void changingADeviceTypeWithoutAnOverrideReseedsCapabilitiesFromTheTemplate() {
        UUID deviceId = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        Room room = new Room(home, "Sala", "sala");
        Device device = new Device(home, room, DeviceType.LIGHT, "Luz", "luz");
        when(devices.findByIdAndHomeUserId(deviceId, "demo")).thenReturn(Optional.of(device));
        when(rooms.findByIdAndHomeUserId(roomId, "demo")).thenReturn(Optional.of(room));

        service.updateDevice("demo", deviceId, roomId, DeviceType.AC, "Luz", null, null);

        verify(traitTemplates).forType(DeviceType.AC);
    }

    @Test
    void updatingADeviceIntoARoomFromAnotherHomeIsRejected() {
        UUID deviceId = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        Device device = new Device(home, new Room(home, "Sala", "sala"), DeviceType.LIGHT, "Luz", "luz");
        Home otherHome = new Home("demo", "Outra", false);
        ReflectionTestUtils.setField(otherHome, "id", UUID.randomUUID());
        Room foreignRoom = new Room(otherHome, "Sala", "sala");
        when(devices.findByIdAndHomeUserId(deviceId, "demo")).thenReturn(Optional.of(device));
        when(rooms.findByIdAndHomeUserId(roomId, "demo")).thenReturn(Optional.of(foreignRoom));

        assertThatThrownBy(() -> service.updateDevice("demo", deviceId, roomId, DeviceType.LIGHT, "Luz", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deletingADevicePublishesATopologyChange() {
        UUID deviceId = UUID.randomUUID();
        Device device = new Device(home, new Room(home, "Sala", "sala"), DeviceType.LIGHT, "Luz", "luz");
        when(devices.findByIdAndHomeUserId(deviceId, "demo")).thenReturn(Optional.of(device));

        service.deleteDevice("demo", deviceId);

        verify(devices).delete(device);
        verify(mqtt).publishTopologyChanged(homeId.toString());
    }

    @Test
    void listingRoomsForAnUnknownHomeIsNotFound() {
        when(homes.findByIdAndUserId(homeId, "demo")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.listRooms("demo", homeId)).isInstanceOf(NoSuchElementException.class);
    }
}
