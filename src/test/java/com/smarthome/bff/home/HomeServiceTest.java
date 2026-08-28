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
}
