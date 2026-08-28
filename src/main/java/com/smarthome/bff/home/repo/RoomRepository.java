package com.smarthome.bff.home.repo;

import com.smarthome.bff.home.domain.Room;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoomRepository extends JpaRepository<Room, UUID> {

    List<Room> findByHomeIdOrderByNameAsc(UUID homeId);

    Optional<Room> findByIdAndHomeUserId(UUID id, String userId);

    boolean existsByHomeIdAndSlug(UUID homeId, String slug);
}
