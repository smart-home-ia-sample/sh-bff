package com.smarthome.bff.home.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "devices", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"home_id", "nickname"}),
        @UniqueConstraint(columnNames = {"home_id", "slug"})
})
public class Device {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "home_id", nullable = false)
    private Home home;

    @ManyToOne(optional = false)
    @JoinColumn(name = "room_id", nullable = false)
    private Room room;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeviceType type;

    /** "apelido"; required, unique within a home. */
    @Column(nullable = false)
    private String nickname;

    /** Stable key the AI layer addresses devices by (e.g. {@code living_room_light});
     * required, unique within a home. Derived from the nickname if not supplied. */
    @Column(nullable = false)
    private String slug;

    /**
     * The device's announced capability descriptor: {@code {"traits":[{"trait":...,
     * "commands":[...], "state":[...], "params":{...}}]}}. Null until the
     * {@code device-sim} announces it over MQTT (seeded devices are pre-filled).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode capabilities;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Device() {
    }

    public Device(Home home, Room room, DeviceType type, String nickname, String slug) {
        this.home = home;
        this.room = room;
        this.type = type;
        this.nickname = nickname;
        this.slug = slug;
    }

    public UUID getId() {
        return id;
    }

    public Home getHome() {
        return home;
    }

    public Room getRoom() {
        return room;
    }

    public void setRoom(Room room) {
        this.room = room;
    }

    public DeviceType getType() {
        return type;
    }

    public void setType(DeviceType type) {
        this.type = type;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public JsonNode getCapabilities() {
        return capabilities;
    }

    public void setCapabilities(JsonNode capabilities) {
        this.capabilities = capabilities;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
