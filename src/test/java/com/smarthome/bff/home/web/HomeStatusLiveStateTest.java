package com.smarthome.bff.home.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarthome.bff.auth.JwtService;
import com.smarthome.bff.home.domain.Device;
import com.smarthome.bff.home.domain.DeviceType;
import com.smarthome.bff.home.repo.DeviceRepository;
import com.smarthome.bff.home.repo.HomeRepository;
import com.smarthome.bff.mqtt.MqttGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The device-state branches of the status / command controllers — the ones the
 * main integration test can't reach because it runs with MQTT disabled. Here the
 * gateway is a mock with a canned read-model.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HomeStatusLiveStateTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    JwtService jwt;
    @Autowired
    ObjectMapper json;
    @Autowired
    HomeRepository homeRepo;
    @Autowired
    DeviceRepository deviceRepo;

    @MockBean
    MqttGateway mqtt;

    private String token;
    private UUID lightId;

    @BeforeEach
    void setUp() throws Exception {
        token = jwt.issue("demo");
        UUID homeId = homeRepo.findByUserIdOrderByCreatedAtAsc("demo").get(0).getId();
        List<Device> devices = deviceRepo.findByHomeIdOrderByNicknameAsc(homeId);
        lightId = devices.stream()
                .filter(d -> d.getType() == DeviceType.LIGHT)
                .map(Device::getId)
                .findFirst()
                .orElseThrow();

        when(mqtt.ready()).thenReturn(true);
        when(mqtt.simulatorOnline()).thenReturn(true);
        when(mqtt.currentState(anyString())).thenReturn(json.readTree("{\"on\":true}"));
        when(mqtt.recentChanges()).thenReturn(List.of());
    }

    private String bearer() {
        return "Bearer " + token;
    }

    @Test
    void snapshotJoinsDeviceStateAndComputesRollups() throws Exception {
        mvc.perform(get("/api/home-status/snapshot").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.simulatorOnline").value(true))
                .andExpect(jsonPath("$.rooms[0].devices[0].state.on").value(true))
                .andExpect(jsonPath("$.rollups.activeDevices").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.rollups.totalWatts").value(org.hamcrest.Matchers.greaterThan(0)));
    }

    @Test
    void aCommandReturnsTheEchoedState() throws Exception {
        when(mqtt.command(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(json.readTree("{\"on\":true}"));

        mvc.perform(post("/api/devices/" + lightId + "/command")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"turn_on\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceId").value(lightId.toString()))
                .andExpect(jsonPath("$.state.on").value(true));
    }

    @Test
    void aCommandThatIsNotEchoedInTimeIs504() throws Exception {
        when(mqtt.command(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(null);

        mvc.perform(post("/api/devices/" + lightId + "/command")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"turn_on\"}"))
                .andExpect(status().is(504));
    }

    @Test
    void theStateEndpointReturnsTheCurrentReadModelEntry() throws Exception {
        mvc.perform(get("/api/devices/" + lightId + "/state").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceId").value(lightId.toString()))
                .andExpect(jsonPath("$.state.on").value(true));
    }
}
