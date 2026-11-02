package com.mailroom.repository;

import com.mailroom.entity.DeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, UUID> {

    List<DeviceToken> findByUserId(UUID userId);

    Optional<DeviceToken> findByToken(String token);

    void deleteByToken(String token);
}
