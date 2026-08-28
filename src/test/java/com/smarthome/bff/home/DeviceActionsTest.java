package com.smarthome.bff.home;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeviceActionsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode descriptor(String traitsJson) {
        try {
            return JSON.readTree("{\"traits\":" + traitsJson + "}");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static final JsonNode ON_OFF = descriptor("""
            [{"trait":"on_off","commands":["turn_on","turn_off"],"state":["on"]}]""");

    private static final JsonNode DIMMABLE = descriptor("""
            [{"trait":"on_off","commands":["turn_on","turn_off"],"state":["on"]},
             {"trait":"brightness","commands":["set_brightness"],"state":["brightness"],
              "params":{"set_brightness":{"type":"integer","min":0,"max":100}}}]""");

    private static final JsonNode AC = descriptor("""
            [{"trait":"on_off","commands":["turn_on","turn_off"],"state":["on"]},
             {"trait":"thermostat","commands":["set_temperature"],"state":["temperature"],
              "params":{"set_temperature":{"type":"number","min":16,"max":30}}}]""");

    private static final JsonNode OPEN_CLOSE = descriptor("""
            [{"trait":"open_close","commands":["open","close"],"state":["open"]}]""");

    private static final JsonNode LOCK = descriptor("""
            [{"trait":"lock","commands":["lock","unlock"],"state":["locked"]}]""");

    private static final JsonNode ARM = descriptor("""
            [{"trait":"arm_disarm","commands":["arm","disarm"],"state":["armed"]}]""");

    @Test
    void switchActionsMapToOnOff() {
        assertThat(DeviceActions.toChanges(ON_OFF, "turn_on", null)).isEqualTo(Map.of("on", true));
        assertThat(DeviceActions.toChanges(AC, "turn_off", null)).isEqualTo(Map.of("on", false));
    }

    @Test
    void openCloseLockUnlockArmDisarm() {
        assertThat(DeviceActions.toChanges(OPEN_CLOSE, "open", null)).isEqualTo(Map.of("open", true));
        assertThat(DeviceActions.toChanges(OPEN_CLOSE, "close", null)).isEqualTo(Map.of("open", false));
        assertThat(DeviceActions.toChanges(LOCK, "lock", null)).isEqualTo(Map.of("locked", true));
        assertThat(DeviceActions.toChanges(LOCK, "unlock", null)).isEqualTo(Map.of("locked", false));
        assertThat(DeviceActions.toChanges(ARM, "arm", null)).isEqualTo(Map.of("armed", true));
        assertThat(DeviceActions.toChanges(ARM, "disarm", null)).isEqualTo(Map.of("armed", false));
    }

    @Test
    void setBrightnessRequiresTheBrightnessTraitAndAValidRange() {
        assertThat(DeviceActions.toChanges(DIMMABLE, "set_brightness", 60.0)).isEqualTo(Map.of("brightness", 60));
        assertThat(DeviceActions.toChanges(DIMMABLE, "turn_on", null)).isEqualTo(Map.of("on", true));

        // a plain on/off light has no brightness trait -> unsupported
        assertThatThrownBy(() -> DeviceActions.toChanges(ON_OFF, "set_brightness", 60.0))
                .hasMessageContaining("not supported by this device");
        assertThatThrownBy(() -> DeviceActions.toChanges(DIMMABLE, "set_brightness", 150.0))
                .hasMessageContaining("between 0 and 100");
        assertThatThrownBy(() -> DeviceActions.toChanges(DIMMABLE, "set_brightness", null))
                .hasMessageContaining("requires a numeric value");
    }

    @Test
    void setTemperatureValidatesRangeAndTrait() {
        assertThat(DeviceActions.toChanges(AC, "set_temperature", 22.0)).isEqualTo(Map.of("temperature", 22.0));
        assertThatThrownBy(() -> DeviceActions.toChanges(AC, "set_temperature", 40.0))
                .hasMessageContaining("between 16 and 30");
        assertThatThrownBy(() -> DeviceActions.toChanges(ON_OFF, "set_temperature", 22.0))
                .hasMessageContaining("not supported by this device");
    }

    @Test
    void actionNotInTheDescriptorIsRejected() {
        assertThatThrownBy(() -> DeviceActions.toChanges(LOCK, "turn_on", null))
                .hasMessageContaining("not supported by this device");
    }

    @Test
    void unknownVerbInTheDescriptorIsRejected() {
        JsonNode weird = descriptor("[{\"trait\":\"x\",\"commands\":[\"explode\"],\"state\":[]}]");
        assertThatThrownBy(() -> DeviceActions.toChanges(weird, "explode", null))
                .hasMessageContaining("unknown action");
    }

    @Test
    void nullOrEmptyDescriptorMeansNotProvisioned() {
        assertThatThrownBy(() -> DeviceActions.toChanges(null, "turn_on", null))
                .isInstanceOf(DeviceActions.NotProvisionedException.class);
        assertThatThrownBy(() -> DeviceActions.toChanges(descriptor("[]"), "turn_on", null))
                .isInstanceOf(DeviceActions.NotProvisionedException.class);
    }

    @Test
    void brightnessRangeComesFromTheDescriptorWhenItOverridesTheDefault() {
        JsonNode narrow = descriptor("""
                [{"trait":"brightness","commands":["set_brightness"],"state":["brightness"],
                  "params":{"set_brightness":{"min":10,"max":90}}}]""");

        assertThat(DeviceActions.toChanges(narrow, "set_brightness", 90.0)).isEqualTo(Map.of("brightness", 90));
        assertThatThrownBy(() -> DeviceActions.toChanges(narrow, "set_brightness", 5.0))
                .hasMessageContaining("between 10 and 90");
        assertThatThrownBy(() -> DeviceActions.toChanges(narrow, "set_brightness", 95.0))
                .hasMessageContaining("between 10 and 90");
    }

    @Test
    void temperatureFallsBackToTheDefaultRangeWhenTheDescriptorHasNoParams() {
        JsonNode noParams = descriptor("""
                [{"trait":"thermostat","commands":["set_temperature"],"state":["temperature"]}]""");

        assertThat(DeviceActions.toChanges(noParams, "set_temperature", 30.0)).isEqualTo(Map.of("temperature", 30.0));
        assertThatThrownBy(() -> DeviceActions.toChanges(noParams, "set_temperature", 15.0))
                .hasMessageContaining("between 16 and 30");
    }

    @Test
    void fractionalBoundsAreShownWithoutTrailingZeros() {
        JsonNode fractional = descriptor("""
                [{"trait":"thermostat","commands":["set_temperature"],"state":["temperature"],
                  "params":{"set_temperature":{"min":16.5,"max":29.5}}}]""");

        assertThatThrownBy(() -> DeviceActions.toChanges(fractional, "set_temperature", 16.0))
                .hasMessageContaining("between 16.5 and 29.5");
    }

    @Test
    void setTemperatureWithoutAValueIsRejected() {
        assertThatThrownBy(() -> DeviceActions.toChanges(AC, "set_temperature", null))
                .hasMessageContaining("requires a numeric value");
    }
}
