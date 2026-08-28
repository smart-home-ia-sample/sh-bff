package com.smarthome.bff.home.domain;

/**
 * Closed vocabulary, mirroring the types in {@code mcp/home/app/state.py}.
 * A device's capabilities derive from its type.
 */
public enum DeviceType {
    LIGHT,           // simple on/off
    DIMMABLE_LIGHT,  // on/off + brightness
    AC,
    CURTAIN,
    DOOR,
    WINDOW,
    TV,
    COFFEE_MAKER,
    REFRIGERATOR,
    MOTION_SENSOR,
    ALARM
}
