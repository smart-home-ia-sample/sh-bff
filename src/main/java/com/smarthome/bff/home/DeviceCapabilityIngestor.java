package com.smarthome.bff.home;

import com.smarthome.bff.home.repo.DeviceRepository;
import com.smarthome.bff.mqtt.MqttGateway;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Persists the capability descriptor a device announces over MQTT
 * ({@code home/{homeId}/{roomSlug}/{deviceId}/capabilities}, retained) onto the
 * device row, so {@link DeviceActions} can validate commands against it and the
 * DTOs can serve it. The announce is the source of truth; the seed / type
 * template only pre-fills.
 */
@Component
public class DeviceCapabilityIngestor {

    private static final Logger log = LoggerFactory.getLogger(DeviceCapabilityIngestor.class);

    private final MqttGateway mqtt;
    private final DeviceRepository devices;

    public DeviceCapabilityIngestor(MqttGateway mqtt, DeviceRepository devices) {
        this.mqtt = mqtt;
        this.devices = devices;
    }

    @PostConstruct
    void subscribe() {
        mqtt.onCapabilities(this::ingest);
    }

    @Transactional
    void ingest(MqttGateway.Capabilities announced) {
        UUID deviceId;
        try {
            deviceId = UUID.fromString(announced.deviceId());
        } catch (IllegalArgumentException e) {
            return; // not a device UUID topic segment — ignore
        }
        devices.findById(deviceId).ifPresent(device -> {
            device.setCapabilities(announced.descriptor());
            devices.save(device);
            log.debug("stored announced capabilities for device {}", deviceId);
        });
    }
}
