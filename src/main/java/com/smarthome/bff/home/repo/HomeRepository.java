package com.smarthome.bff.home.repo;

import com.smarthome.bff.home.domain.Home;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HomeRepository extends JpaRepository<Home, UUID> {

    List<Home> findByUserIdOrderByCreatedAtAsc(String userId);

    Optional<Home> findByIdAndUserId(UUID id, String userId);

    Optional<Home> findFirstByUserIdAndIsDefaultTrue(String userId);

    boolean existsByUserId(String userId);
}
