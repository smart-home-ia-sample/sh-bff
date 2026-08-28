package com.smarthome.bff.home.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.UuidGenerator;

import java.util.UUID;

@Entity
@Table(name = "rooms", uniqueConstraints = @UniqueConstraint(columnNames = {"home_id", "slug"}))
public class Room {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "home_id", nullable = false)
    private Home home;

    @Column(nullable = false)
    private String name;

    /** Stable key (e.g. {@code living_room}); MQTT topic segment and orchestrator room match. */
    @Column(nullable = false)
    private String slug;

    protected Room() {
    }

    public Room(Home home, String name, String slug) {
        this.home = home;
        this.name = name;
        this.slug = slug;
    }

    public UUID getId() {
        return id;
    }

    public Home getHome() {
        return home;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }
}
