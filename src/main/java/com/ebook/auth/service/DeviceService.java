package com.ebook.auth.service;

import com.ebook.auth.entity.Device;
import com.ebook.user.entity.User;
import com.ebook.auth.repository.DeviceRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.Optional;

@ApplicationScoped
public class DeviceService {

    private final DeviceRepository deviceRepository;

    public DeviceService(DeviceRepository deviceRepository) {
        this.deviceRepository = deviceRepository;
    }

    @Transactional
    public Device registerOrUpdateDevice(User user, String fingerprint) {
        Optional<Device> existing = deviceRepository.findByUserIdAndFingerprint(user.getId(), fingerprint);
        if (existing.isPresent()) {
            Device device = existing.get();
            return deviceRepository.update(device);
        } else {
            Device device = new Device();
            device.setUser(user);
            device.setDeviceFingerprint(fingerprint);
            device.setTrusted(false);
            deviceRepository.save(device);
            return device;
        }
    }
}
