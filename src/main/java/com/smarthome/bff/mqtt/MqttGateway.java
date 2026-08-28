package com.smarthome.bff.mqtt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The BFF's link to the simulated physical layer over MQTT. Keeps the latest
 * {@code .../state} per device (the read-model the Dashboard will use), publishes
 * {@code .../set} / {@code .../get} commands, and lets a caller block for the
 * echoed state after a command.
 */
@Component
public class MqttGateway implements MqttCallback {

    private static final Logger log = LoggerFactory.getLogger(MqttGateway.class);
    private static final String STATE_TOPIC_FILTER = "home/+/+/+/state";
    private static final String CAPABILITIES_TOPIC_FILTER = "home/+/+/+/capabilities";
    private static final String SIMULATOR_STATUS_TOPIC = "home/simulator/status";
    private static final int RECENT_CHANGES_LIMIT = 50;

    private final MqttProperties props;
    private final ObjectMapper json;

    private final Map<String, JsonNode> latestState = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<JsonNode>> waiters = new ConcurrentHashMap<>();
    private final Deque<StateChange> recentChanges = new ArrayDeque<>();
    private final List<Consumer<StateChange>> changeListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<Boolean>> simulatorListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<Capabilities>> capabilitiesListeners = new CopyOnWriteArrayList<>();

    private volatile MqttAsyncClient client;
    private volatile boolean simulatorOnline;

    /** A device's state at a point in time — the raw material of the events feed. */
    public record StateChange(String deviceId, JsonNode state, long at) {
    }

    /** A device self-announcing its capability descriptor ({@code {"traits":[...]}}). */
    public record Capabilities(String deviceId, JsonNode descriptor) {
    }

    public MqttGateway(MqttProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
    }

    @PostConstruct
    void connect() {
        if (!props.enabled()) {
            log.info("MQTT disabled (bff.mqtt.enabled=false); device commands will 503");
            return;
        }
        try {
            client = new MqttAsyncClient("tcp://" + props.host() + ":" + props.port(),
                    "bff-" + System.nanoTime(), new MemoryPersistence());
            client.setCallback(this);
            MqttConnectOptions opts = new MqttConnectOptions();
            opts.setAutomaticReconnect(true);
            opts.setCleanSession(true);
            opts.setConnectionTimeout(5);
            if (props.username() != null && !props.username().isBlank()) {
                opts.setUserName(props.username());
                opts.setPassword(props.password() == null ? new char[0] : props.password().toCharArray());
            }
            client.connect(opts).waitForCompletion(TimeUnit.SECONDS.toMillis(10));
            client.subscribe(STATE_TOPIC_FILTER, 1);
            client.subscribe(CAPABILITIES_TOPIC_FILTER, 1);
            client.subscribe(SIMULATOR_STATUS_TOPIC, 1);
            log.info("MQTT connected to {}:{}", props.host(), props.port());
        } catch (MqttException e) {
            // Non-fatal: the app still serves auth + CRUD. Paho keeps retrying
            // in the background once connect has been attempted; if the initial
            // attempt itself failed, ready() stays false until a manual restart.
            log.warn("MQTT connect failed ({}); device commands will 503 until it recovers", e.getMessage());
        }
    }

    @PreDestroy
    void disconnect() {
        try {
            if (client != null && client.isConnected()) {
                client.disconnect().waitForCompletion(2000);
            }
        } catch (MqttException ignored) {
            // shutting down
        }
    }

    public boolean ready() {
        return client != null && client.isConnected();
    }

    public boolean simulatorOnline() {
        return simulatorOnline;
    }

    public JsonNode currentState(String deviceId) {
        return latestState.get(deviceId);
    }

    /** Most recent device state changes, oldest first. */
    public List<StateChange> recentChanges() {
        synchronized (recentChanges) {
            return List.copyOf(recentChanges);
        }
    }

    /** Notified with the change after every device {@code .../state} message. */
    public void onChange(Consumer<StateChange> listener) {
        changeListeners.add(listener);
    }

    /** Notified when the simulator's online status flips. */
    public void onSimulatorStatus(Consumer<Boolean> listener) {
        simulatorListeners.add(listener);
    }

    /** Notified when a device announces (or re-announces) its capability descriptor. */
    public void onCapabilities(Consumer<Capabilities> listener) {
        capabilitiesListeners.add(listener);
    }

    public void publishTopologyChanged(String homeId) {
        if (ready()) {
            publish("home/" + homeId + "/topology/changed", Map.of());
        }
    }

    public void publishGet(String homeId, String roomSlug, String deviceId, String type) {
        publish(topic(homeId, roomSlug, deviceId, "get"), Map.of("type", type));
    }

    /** Publish a command and block until the device echoes its new state, or time out. */
    public JsonNode command(String homeId, String roomSlug, String deviceId, String type,
                            Map<String, Object> changes) {
        CompletableFuture<JsonNode> echo = new CompletableFuture<>();
        waiters.put(deviceId, echo);
        try {
            publish(topic(homeId, roomSlug, deviceId, "set"), Map.of("type", type, "changes", changes));
            return echo.get(props.commandTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return null; // timeout / interruption -> caller maps to 504
        } finally {
            waiters.remove(deviceId, echo);
        }
    }

    private void publish(String topic, Object payload) {
        try {
            byte[] body = json.writeValueAsBytes(payload);
            client.publish(topic, new MqttMessage(body) {{
                setQos(1);
            }});
        } catch (Exception e) {
            throw new IllegalStateException("MQTT publish failed: " + e.getMessage(), e);
        }
    }

    private static String topic(String homeId, String roomSlug, String deviceId, String action) {
        return "home/" + homeId + "/" + roomSlug + "/" + deviceId + "/" + action;
    }

    // ---- MqttCallback -------------------------------------------------------

    @Override
    public void messageArrived(String topic, MqttMessage message) throws Exception {
        String payload = new String(message.getPayload(), StandardCharsets.UTF_8);

        if (SIMULATOR_STATUS_TOPIC.equals(topic)) {
            simulatorOnline = "online".equals(payload.trim());
            for (Consumer<Boolean> listener : simulatorListeners) {
                safe(() -> listener.accept(simulatorOnline));
            }
            return;
        }

        String[] parts = topic.split("/");
        if (parts.length != 5) {
            return;
        }

        if ("capabilities".equals(parts[4])) {
            JsonNode descriptor = json.readTree(payload);
            Capabilities announced = new Capabilities(parts[3], descriptor);
            for (Consumer<Capabilities> listener : capabilitiesListeners) {
                safe(() -> listener.accept(announced));
            }
            return;
        }

        if (!"state".equals(parts[4])) {
            return;
        }
        String deviceId = parts[3];
        JsonNode state = json.readTree(payload);
        latestState.put(deviceId, state);

        StateChange change = new StateChange(deviceId, state, System.currentTimeMillis());
        synchronized (recentChanges) {
            recentChanges.addLast(change);
            while (recentChanges.size() > RECENT_CHANGES_LIMIT) {
                recentChanges.removeFirst();
            }
        }

        CompletableFuture<JsonNode> waiter = waiters.get(deviceId);
        if (waiter != null) {
            waiter.complete(state);
        }
        for (Consumer<StateChange> listener : changeListeners) {
            safe(() -> listener.accept(change));
        }
    }

    private void safe(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            log.warn("home-status listener failed: {}", e.getMessage());
        }
    }

    @Override
    public void connectionLost(Throwable cause) {
        log.warn("MQTT connection lost: {}", cause == null ? "unknown" : cause.getMessage());
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // no-op
    }
}
