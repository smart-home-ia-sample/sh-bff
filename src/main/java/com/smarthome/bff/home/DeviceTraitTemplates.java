package com.smarthome.bff.home;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarthome.bff.home.domain.DeviceType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Default capability descriptor per device type.
 *
 * <p>Used <b>only</b> to pre-fill {@code devices.capabilities} at seed time and as
 * the fallback when a device is created while the {@code device-sim} is
 * unreachable. It is <b>never consulted at command time</b> — {@link DeviceActions}
 * validates against the descriptor stored on the device, which the {@code device-sim}
 * announces over MQTT (and normally overwrites this pre-fill with identical content).
 *
 * <p>The {@code device-sim} keeps its own copy of this table (see
 * {@code device-sim/app/simulator.py}); the duplication is deliberate — two small
 * tables in two languages — so the BFF stays testable and usable offline.
 */
@Component
public class DeviceTraitTemplates {

    private static final String ON_OFF = """
            {"trait":"on_off","commands":["turn_on","turn_off"],"state":["on"],
             "description":"liga e desliga o aparelho",
             "examples":["liga a TV","desliga a cafeteira","acende a luz","apaga a luz"]}""";

    private static final String BRIGHTNESS = """
            {"trait":"brightness","commands":["set_brightness"],"state":["brightness"],
             "params":{"set_brightness":{"type":"integer","min":0,"max":100,"unit":"%"}},
             "description":"ajusta o brilho",
             "examples":["diminui o brilho","coloca a luz em 30%","deixa mais forte"]}""";

    private static final String THERMOSTAT = """
            {"trait":"thermostat","commands":["set_temperature"],"state":["temperature"],
             "params":{"set_temperature":{"type":"number","min":16,"max":30,"unit":"C"}},
             "description":"define a temperatura alvo",
             "examples":["ajusta para 22 graus","esfria o quarto","aumenta a temperatura"]}""";

    private static final String OPEN_CLOSE = """
            {"trait":"open_close","commands":["open","close"],"state":["open"],
             "description":"abre e fecha",
             "examples":["abre a cortina","fecha a janela"]}""";

    private static final String LOCK = """
            {"trait":"lock","commands":["lock","unlock"],"state":["locked"],
             "description":"tranca e destranca",
             "examples":["tranca a porta","destranca a porta da frente"]}""";

    private static final String ARM_DISARM = """
            {"trait":"arm_disarm","commands":["arm","disarm"],"state":["armed"],
             "description":"arma e desarma o alarme",
             "examples":["arma o alarme","desarma o alarme"]}""";

    private static final String OCCUPANCY = """
            {"trait":"occupancy","commands":[],"state":["active"],
             "description":"detecta presenca (somente leitura)",
             "examples":["tem alguem na sala?"]}""";

    private static final Map<DeviceType, List<String>> TRAITS = new EnumMap<>(DeviceType.class);

    static {
        TRAITS.put(DeviceType.LIGHT, List.of(ON_OFF));
        TRAITS.put(DeviceType.DIMMABLE_LIGHT, List.of(ON_OFF, BRIGHTNESS));
        TRAITS.put(DeviceType.AC, List.of(ON_OFF, THERMOSTAT));
        TRAITS.put(DeviceType.TV, List.of(ON_OFF));
        TRAITS.put(DeviceType.COFFEE_MAKER, List.of(ON_OFF));
        TRAITS.put(DeviceType.REFRIGERATOR, List.of(ON_OFF));
        TRAITS.put(DeviceType.CURTAIN, List.of(OPEN_CLOSE));
        TRAITS.put(DeviceType.WINDOW, List.of(OPEN_CLOSE));
        TRAITS.put(DeviceType.DOOR, List.of(LOCK));
        TRAITS.put(DeviceType.ALARM, List.of(ARM_DISARM));
        TRAITS.put(DeviceType.MOTION_SENSOR, List.of(OCCUPANCY));
    }

    private final ObjectMapper json;

    public DeviceTraitTemplates(ObjectMapper json) {
        this.json = json;
    }

    /** The default {@code {"traits":[...]}} descriptor for a type, or {@code null} if unknown. */
    public JsonNode forType(DeviceType type) {
        List<String> fragments = TRAITS.get(type);
        if (fragments == null) {
            return null;
        }
        try {
            return json.readTree("{\"traits\":[" + String.join(",", fragments) + "]}");
        } catch (Exception e) {
            throw new IllegalStateException("bad trait template for " + type, e);
        }
    }
}
