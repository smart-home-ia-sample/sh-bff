package com.smarthome.bff.home;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * Translates a semantic device command ("turn_on", "set_temperature") into the
 * concrete state change to publish, validating it against the device's
 * <b>announced capability descriptor</b> (the {@code {"traits":[...]}} the
 * {@code device-sim} publishes and the BFF stores on the device). No per-type
 * knowledge lives here any more — only the semantic verb vocabulary.
 * Pure — no I/O — so the rules are unit-tested directly.
 */
public final class DeviceActions {

    private DeviceActions() {
    }

    /** Raised when a command targets a device that has no descriptor yet. Maps to 409. */
    public static final class NotProvisionedException extends IllegalStateException {
        public NotProvisionedException() {
            super("device not provisioned yet (no capabilities announced)");
        }
    }

    /**
     * @param descriptor the device's stored {@code {"traits":[...]}} descriptor (may be null)
     * @return the {@code changes} map for the MQTT {@code set} payload
     */
    public static Map<String, Object> toChanges(JsonNode descriptor, String action, Double value) {
        JsonNode traits = descriptor == null ? null : descriptor.get("traits");
        if (traits == null || !traits.isArray() || traits.isEmpty()) {
            throw new NotProvisionedException();
        }

        JsonNode owner = traitFor(traits, action);
        if (owner == null) {
            throw new IllegalArgumentException("action '" + action + "' is not supported by this device");
        }

        return switch (action) {
            case "turn_on" -> Map.of("on", true);
            case "turn_off" -> Map.of("on", false);
            case "open" -> Map.of("open", true);
            case "close" -> Map.of("open", false);
            case "lock" -> Map.of("locked", true);
            case "unlock" -> Map.of("locked", false);
            case "arm" -> Map.of("armed", true);
            case "disarm" -> Map.of("armed", false);
            case "set_brightness" -> Map.of("brightness", (int) Math.round(numericParam(owner, action, value, 0, 100)));
            case "set_temperature" -> Map.of("temperature", numericParam(owner, action, value, 16, 30));
            default -> throw new IllegalArgumentException("unknown action '" + action + "'");
        };
    }

    /** The first trait that lists {@code action} among its {@code commands}, or null. */
    private static JsonNode traitFor(JsonNode traits, String action) {
        for (JsonNode trait : traits) {
            JsonNode commands = trait.get("commands");
            if (commands != null && commands.isArray()) {
                for (JsonNode c : commands) {
                    if (c.asText().equals(action)) {
                        return trait;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Validates a numeric command param against the trait's {@code params.<action>}
     * schema ({@code min}/{@code max}), falling back to the given defaults when the
     * descriptor omits a range.
     */
    private static double numericParam(JsonNode trait, String action, Double value, double defMin, double defMax) {
        if (value == null) {
            throw new IllegalArgumentException("action '" + action + "' requires a numeric value");
        }
        double min = defMin;
        double max = defMax;
        JsonNode spec = trait.path("params").path(action);
        if (spec.hasNonNull("min")) {
            min = spec.get("min").asDouble();
        }
        if (spec.hasNonNull("max")) {
            max = spec.get("max").asDouble();
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException(action + " value must be between "
                    + trimmed(min) + " and " + trimmed(max));
        }
        return value;
    }

    private static String trimmed(double d) {
        return d == Math.rint(d) ? Long.toString((long) d) : Double.toString(d);
    }
}
