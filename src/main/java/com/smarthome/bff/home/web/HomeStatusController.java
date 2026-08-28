package com.smarthome.bff.home.web;

import com.smarthome.bff.auth.CurrentUser;
import com.smarthome.bff.home.HomeStatusService;
import com.smarthome.bff.home.HomeStatusService.Delta;
import com.smarthome.bff.home.HomeStatusService.Snapshot;
import com.smarthome.bff.mqtt.MqttGateway;
import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The Dashboard's live feed. {@code GET /api/home-status} opens an SSE stream:
 * one full {@code snapshot} event on connect, then a small {@code device} event
 * for each device that changes ({id, state, rollups}) and a {@code simulator}
 * event when the simulator goes on/offline. {@code /snapshot} is the full shape,
 * one-shot. Served entirely from the BFF's read-model — never the orchestrator.
 */
@RestController
public class HomeStatusController {

    private final HomeStatusService service;
    private final MqttGateway mqtt;
    private final CopyOnWriteArrayList<UserEmitter> emitters = new CopyOnWriteArrayList<>();

    public HomeStatusController(HomeStatusService service, MqttGateway mqtt) {
        this.service = service;
        this.mqtt = mqtt;
    }

    private record UserEmitter(String user, SseEmitter emitter) {
    }

    @PostConstruct
    void subscribe() {
        mqtt.onChange(change -> broadcastDevice(change.deviceId(), change.at()));
        mqtt.onSimulatorStatus(this::broadcastSimulator);
    }

    @GetMapping("/api/home-status")
    public SseEmitter stream(@CurrentUser String user) {
        SseEmitter emitter = new SseEmitter(0L);
        UserEmitter registered = new UserEmitter(user, emitter);
        emitters.add(registered);
        emitter.onCompletion(() -> emitters.remove(registered));
        emitter.onTimeout(() -> emitters.remove(registered));
        emitter.onError(e -> emitters.remove(registered));

        try {
            emitter.send(SseEmitter.event().name("snapshot").data(service.forUser(user)));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    @GetMapping("/api/home-status/snapshot")
    public Snapshot snapshot(@CurrentUser String user) {
        return service.forUser(user);
    }

    private void broadcastDevice(String deviceId, long at) {
        for (UserEmitter ue : emitters) {
            try {
                Delta delta = service.deltaForDevice(ue.user(), deviceId, at);
                if (delta != null) {
                    ue.emitter().send(SseEmitter.event().name("device").data(delta));
                }
            } catch (Exception e) {
                emitters.remove(ue);
            }
        }
    }

    private void broadcastSimulator(boolean online) {
        for (UserEmitter ue : emitters) {
            try {
                ue.emitter().send(SseEmitter.event().name("simulator").data(Map.of("simulatorOnline", online)));
            } catch (Exception e) {
                emitters.remove(ue);
            }
        }
    }

    /** Keep idle SSE connections alive through proxies. */
    @Scheduled(fixedRate = 20_000)
    void heartbeat() {
        for (UserEmitter ue : emitters) {
            try {
                ue.emitter().send(SseEmitter.event().comment("keep-alive"));
            } catch (Exception e) {
                emitters.remove(ue);
            }
        }
    }
}
