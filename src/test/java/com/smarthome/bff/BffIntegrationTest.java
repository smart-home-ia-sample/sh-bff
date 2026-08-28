package com.smarthome.bff;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarthome.bff.auth.JwtService;
import com.smarthome.bff.home.repo.DeviceRepository;
import com.smarthome.bff.home.repo.HomeRepository;
import com.smarthome.bff.seed.DataSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional // each test rolls back; the startup seed stays committed
class BffIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtService jwt;

    @Autowired
    ObjectMapper json;

    @Autowired
    DataSeeder seeder;

    @Autowired
    HomeRepository homeRepo;

    @Autowired
    DeviceRepository deviceRepo;

    private String login() throws Exception {
        MvcResult res = mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"demo\",\"password\":\"demo\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(res.getResponse().getContentAsString()).get("access_token").asText();
    }

    private JsonNode body(MvcResult res) throws Exception {
        return json.readTree(res.getResponse().getContentAsString());
    }

    // ---- auth ----------------------------------------------------------------

    @Test
    void loginRejectsWrongPassword() throws Exception {
        mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"demo\",\"password\":\"nope\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginRejectsUnknownUser() throws Exception {
        mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"mallory\",\"password\":\"demo\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginRejectsMissingField() throws Exception {
        mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"demo\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void loginAcceptsFormUrlEncodedBody() throws Exception {
        MvcResult res = mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", "demo")
                        .param("password", "demo"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(body(res).get("access_token").asText()).isNotBlank();
    }

    @Test
    void loginFormUrlEncodedRejectsWrongPassword() throws Exception {
        mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", "demo")
                        .param("password", "nope"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginFormUrlEncodedRejectsMissingField() throws Exception {
        mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", "demo"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void apiRequiresBearerToken() throws Exception {
        mvc.perform(get("/api/homes")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/homes").header("Authorization", "Bearer garbage"))
                .andExpect(status().isUnauthorized());
    }

    // ---- seed --------------------------------------------------------------

    @Test
    void seedGivesDemoUserAModelHome() throws Exception {
        String token = login();
        MvcResult homes = mvc.perform(get("/api/homes").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Casa Modelo"))
                .andExpect(jsonPath("$[0].isDefault").value(true))
                .andReturn();
        String homeId = body(homes).get(0).get("id").asText();

        mvc.perform(get("/api/homes/" + homeId + "/rooms").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5));

        MvcResult devices = mvc.perform(get("/api/homes/" + homeId + "/devices")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(13))
                .andReturn();

        // the seeded slugs are the legacy keys the AI layer has always used
        java.util.Set<String> slugs = new java.util.HashSet<>();
        for (JsonNode d : body(devices)) {
            slugs.add(d.get("slug").asText());
            // every seeded device is pre-filled with a capability descriptor
            assertThat(d.get("capabilities").get("traits").isArray()).isTrue();
        }
        assertThat(slugs).contains("living_room_light", "bedroom_ac", "front_door", "alarm", "motion_sensor");

        // the coffee maker (a plain appliance) advertises on_off
        JsonNode coffee = null;
        for (JsonNode d : body(devices)) {
            if (d.get("slug").asText().equals("kitchen_coffee_maker")) coffee = d;
        }
        assertThat(coffee).isNotNull();
        assertThat(coffee.get("capabilities").get("traits").get(0).get("commands").toString())
                .contains("turn_on", "turn_off");
    }

    // ---- crud ------------------------------------------------------------------

    @Test
    void deviceCrudRoundTrip() throws Exception {
        String token = login();
        String auth = "Bearer " + token;
        String homeId = body(mvc.perform(get("/api/homes").header("Authorization", auth)).andReturn())
                .get(0).get("id").asText();
        String roomId = body(mvc.perform(get("/api/homes/" + homeId + "/rooms").header("Authorization", auth))
                .andReturn()).get(0).get("id").asText();

        MvcResult created = mvc.perform(post("/api/homes/" + homeId + "/devices")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roomId\":\"" + roomId + "\",\"type\":\"LIGHT\",\"nickname\":\"Luz nova\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("LIGHT"))
                .andReturn();
        String deviceId = body(created).get("id").asText();

        mvc.perform(put("/api/devices/" + deviceId)
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roomId\":\"" + roomId + "\",\"type\":\"CURTAIN\",\"nickname\":\"Luz nova\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("CURTAIN"));

        mvc.perform(delete("/api/devices/" + deviceId).header("Authorization", auth))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/devices/" + deviceId).header("Authorization", auth))
                .andExpect(status().isNotFound());
    }

    @Test
    void duplicateNicknameIsConflict() throws Exception {
        String token = login();
        String auth = "Bearer " + token;
        String homeId = body(mvc.perform(get("/api/homes").header("Authorization", auth)).andReturn())
                .get(0).get("id").asText();
        String roomId = body(mvc.perform(get("/api/homes/" + homeId + "/rooms").header("Authorization", auth))
                .andReturn()).get(0).get("id").asText();

        mvc.perform(post("/api/homes/" + homeId + "/devices")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roomId\":\"" + roomId + "\",\"type\":\"LIGHT\",\"nickname\":\"Luz da sala\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void anotherUserCannotSeeThisHome() throws Exception {
        String demoToken = login();
        String homeId = body(mvc.perform(get("/api/homes").header("Authorization", "Bearer " + demoToken))
                .andReturn()).get(0).get("id").asText();

        String mallory = jwt.issue("mallory");
        mvc.perform(get("/api/homes/" + homeId).header("Authorization", "Bearer " + mallory))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/homes").header("Authorization", "Bearer " + mallory))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void unknownDeviceTypeIsBadRequest() throws Exception {
        String auth = "Bearer " + login();
        String homeId = body(mvc.perform(get("/api/homes").header("Authorization", auth)).andReturn())
                .get(0).get("id").asText();
        String roomId = body(mvc.perform(get("/api/homes/" + homeId + "/rooms").header("Authorization", auth))
                .andReturn()).get(0).get("id").asText();

        mvc.perform(post("/api/homes/" + homeId + "/devices")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roomId\":\"" + roomId + "\",\"type\":\"BOGUS\",\"nickname\":\"X\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void homeCrudRoundTrip() throws Exception {
        String auth = "Bearer " + login();

        MvcResult created = mvc.perform(post("/api/homes").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Casa de praia\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isDefault").value(false)) // first home (seeded) stays default
                .andReturn();
        String secondId = body(created).get("id").asText();

        mvc.perform(put("/api/homes/" + secondId).header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Casa de praia\",\"isDefault\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isDefault").value(true));

        // making the 2nd home default must have cleared it on the seeded one
        JsonNode list = body(mvc.perform(get("/api/homes").header("Authorization", auth)).andReturn());
        assertThat(list.size()).isEqualTo(2);
        long defaults = 0;
        for (JsonNode h : list) {
            if (h.get("isDefault").asBoolean()) defaults++;
        }
        assertThat(defaults).isEqualTo(1);

        mvc.perform(delete("/api/homes/" + secondId).header("Authorization", auth))
                .andExpect(status().isNoContent());
        assertThat(body(mvc.perform(get("/api/homes").header("Authorization", auth)).andReturn()).size())
                .isEqualTo(1);
    }

    @Test
    void roomCrudAndConflicts() throws Exception {
        String auth = "Bearer " + login();
        String homeId = body(mvc.perform(get("/api/homes").header("Authorization", auth)).andReturn())
                .get(0).get("id").asText();

        String roomId = body(mvc.perform(post("/api/homes/" + homeId + "/rooms").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Escritório\",\"slug\":\"office\"}"))
                .andExpect(status().isCreated())
                .andReturn()).get("id").asText();

        // duplicate slug
        mvc.perform(post("/api/homes/" + homeId + "/rooms").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Outro\",\"slug\":\"office\"}"))
                .andExpect(status().isConflict());

        // put a device in it, then the room can't be deleted
        String deviceId = body(mvc.perform(post("/api/homes/" + homeId + "/devices").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roomId\":\"" + roomId + "\",\"type\":\"LIGHT\",\"nickname\":\"Luz do escritório\"}"))
                .andExpect(status().isCreated())
                .andReturn()).get("id").asText();

        mvc.perform(delete("/api/rooms/" + roomId).header("Authorization", auth))
                .andExpect(status().isConflict());

        mvc.perform(delete("/api/devices/" + deviceId).header("Authorization", auth))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/rooms/" + roomId).header("Authorization", auth))
                .andExpect(status().isNoContent());
    }

    // ---- device command (MQTT disabled in tests) -----------------------

    @Test
    void deviceCommandIs503WhenTheDeviceLinkIsDown() throws Exception {
        String auth = "Bearer " + login();
        String homeId = body(mvc.perform(get("/api/homes").header("Authorization", auth)).andReturn())
                .get(0).get("id").asText();
        JsonNode devices = body(mvc.perform(get("/api/homes/" + homeId + "/devices").header("Authorization", auth))
                .andReturn());
        String lightId = null;
        for (JsonNode d : devices) {
            if (d.get("type").asText().equals("LIGHT")) lightId = d.get("id").asText();
        }

        // valid action, but bff.mqtt.enabled=false in the test profile -> link down
        mvc.perform(post("/api/devices/" + lightId + "/command").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"turn_off\"}"))
                .andExpect(status().is(503));
    }

    @Test
    void deviceCommandRejectsBadInputBeforeTheDeviceLink() throws Exception {
        String auth = "Bearer " + login();
        String homeId = body(mvc.perform(get("/api/homes").header("Authorization", auth)).andReturn())
                .get(0).get("id").asText();
        JsonNode devices = body(mvc.perform(get("/api/homes/" + homeId + "/devices").header("Authorization", auth))
                .andReturn());
        String doorId = null;
        for (JsonNode d : devices) {
            if (d.get("type").asText().equals("DOOR")) doorId = d.get("id").asText();
        }

        // unknown device -> 404
        mvc.perform(post("/api/devices/" + java.util.UUID.randomUUID() + "/command").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"turn_off\"}"))
                .andExpect(status().isNotFound());

        // missing action -> 400
        mvc.perform(post("/api/devices/" + doorId + "/command").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        // action invalid for the device type -> 400
        mvc.perform(post("/api/devices/" + doorId + "/command").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"turn_on\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deviceCommandIs409WhenTheDeviceHasNoCapabilitiesYet() throws Exception {
        String auth = "Bearer " + login();
        String homeId = body(mvc.perform(get("/api/homes").header("Authorization", auth)).andReturn())
                .get(0).get("id").asText();
        JsonNode devices = body(mvc.perform(get("/api/homes/" + homeId + "/devices").header("Authorization", auth))
                .andReturn());
        String lightId = null;
        for (JsonNode d : devices) {
            if (d.get("type").asText().equals("LIGHT")) lightId = d.get("id").asText();
        }

        // simulate a row that predates the descriptor (not yet announced)
        var device = deviceRepo.findById(java.util.UUID.fromString(lightId)).orElseThrow();
        device.setCapabilities(null);
        deviceRepo.save(device);

        mvc.perform(post("/api/devices/" + lightId + "/command").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"turn_off\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void appliedCapabilityOverrideOnCreateIsStored() throws Exception {
        String auth = "Bearer " + login();
        String homeId = body(mvc.perform(get("/api/homes").header("Authorization", auth)).andReturn())
                .get(0).get("id").asText();
        String roomId = body(mvc.perform(get("/api/homes/" + homeId + "/rooms").header("Authorization", auth))
                .andReturn()).get(0).get("id").asText();

        MvcResult created = mvc.perform(post("/api/homes/" + homeId + "/devices").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roomId\":\"" + roomId + "\",\"type\":\"LIGHT\",\"nickname\":\"Abajur\","
                                + "\"capabilities\":{\"traits\":[{\"trait\":\"on_off\",\"commands\":[\"turn_on\"],"
                                + "\"state\":[\"on\"]}]}}"))
                .andExpect(status().isCreated())
                .andReturn();

        assertThat(body(created).get("capabilities").get("traits").get(0).get("commands").get(0).asText())
                .isEqualTo("turn_on");
    }

    // ---- SPA + health -----------------------------------------------------

    @Test
    void servesTheSpaShellAndFallsBackForClientRoutes() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk());
        mvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(org.hamcrest.Matchers.containsString("test-spa-shell")));
    }

    @Test
    void healthEndpointIsPublic() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    // ---- seeder ----------------------------------------------------------------

    @Test
    void seederIsIdempotent() {
        long homesBefore = homeRepo.count();
        long devicesBefore = deviceRepo.count();

        seeder.run(null); // a second run must be a no-op, not a duplicate or a throw

        assertThat(homeRepo.count()).isEqualTo(homesBefore);
        assertThat(deviceRepo.count()).isEqualTo(devicesBefore);
    }

    // ---- home-status (served locally from the read-model) ----------------

    @Test
    void homeStatusSnapshotHasTheFullTopologyWithNoStateWhenMqttIsOff() throws Exception {
        String auth = "Bearer " + login();
        mvc.perform(get("/api/home-status/snapshot").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.simulatorOnline").value(false))
                .andExpect(jsonPath("$.rooms.length()").value(5))
                .andExpect(jsonPath("$.rooms[?(@.slug == 'living_room')].devices.length()").exists())
                .andExpect(jsonPath("$.rollups.totalWatts").value(0))
                .andExpect(jsonPath("$.rollups.activeDevices").value(0))
                .andExpect(jsonPath("$.events.length()").value(0));
    }

    @Test
    void homeStatusRequiresAuth() throws Exception {
        mvc.perform(get("/api/home-status/snapshot")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/home-status")).andExpect(status().isUnauthorized());
    }

    @Test
    void homeStatusStreamOpensAnSseConnection() throws Exception {
        String auth = "Bearer " + login();
        mvc.perform(get("/api/home-status").header("Authorization", auth))
                .andExpect(request().asyncStarted())
                .andExpect(status().isOk());
    }

    // ---- orchestrator proxy -----------------------------------------------

    @Test
    void aguiRunIsGuardedBeforeForwarding() throws Exception {
        // No token: the JWT filter 401s before any attempt to reach the
        // (unreachable, in tests) orchestrator.
        mvc.perform(post("/api/agui/run").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
