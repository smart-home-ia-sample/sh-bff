package com.smarthome.bff.home.repo;

import com.smarthome.bff.home.domain.Device;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeviceRepository extends JpaRepository<Device, UUID> {

    List<Device> findByHomeIdOrderByNicknameAsc(UUID homeId);

    List<Device> findByRoomIdOrderByNicknameAsc(UUID roomId);

    Optional<Device> findByIdAndHomeUserId(UUID id, String userId);

    boolean existsByHomeIdAndNickname(UUID homeId, String nickname);

    boolean existsByHomeIdAndSlug(UUID homeId, String slug);
}
