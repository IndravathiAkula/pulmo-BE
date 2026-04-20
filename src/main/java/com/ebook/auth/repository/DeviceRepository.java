package com.ebook.auth.repository;

import com.ebook.auth.entity.Device;
import com.ebook.common.repository.BaseRepository;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class DeviceRepository extends BaseRepository<Device, UUID> {

    public Optional<Device> findByUserIdAndFingerprint(UUID userId, String fingerprint) {
        return find("user.id = ?1 and deviceFingerprint = ?2", userId, fingerprint).firstResultOptional();
    }
}
