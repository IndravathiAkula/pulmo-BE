package com.ebook.common.service;

import com.ebook.common.entity.ConfigParam;
import com.ebook.common.repository.ConfigParamRepository;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Centralized configuration service backed by m_config_params table.
 * Caches all values in memory at startup. Use refresh() to reload.
 */
@ApplicationScoped
public class ConfigService {

    private static final Logger LOG = Logger.getLogger(ConfigService.class);

    private final ConfigParamRepository configParamRepository;
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    public ConfigService(ConfigParamRepository configParamRepository) {
        this.configParamRepository = configParamRepository;
    }

    @Transactional
    void onStartup(@Observes StartupEvent event) {
        refreshCache();
        LOG.infof("ConfigService loaded %d parameters from DB", cache.size());
    }

    public String getString(String key, String defaultValue) {
        return cache.getOrDefault(key, defaultValue);
    }

    public int getInt(String key, int defaultValue) {
        String val = cache.get(key);
        if (val == null) return defaultValue;
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            LOG.warnf("Config key '%s' has non-integer value '%s', using default %d", key, val, defaultValue);
            return defaultValue;
        }
    }

    public long getLong(String key, long defaultValue) {
        String val = cache.get(key);
        if (val == null) return defaultValue;
        try {
            return Long.parseLong(val.trim());
        } catch (NumberFormatException e) {
            LOG.warnf("Config key '%s' has non-long value '%s', using default %d", key, val, defaultValue);
            return defaultValue;
        }
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String val = cache.get(key);
        if (val == null) return defaultValue;
        return Boolean.parseBoolean(val.trim());
    }

    public void refreshCache() {
        List<ConfigParam> all = configParamRepository.listAll();
        cache.clear();
        for (ConfigParam param : all) {
            String effectiveValue = param.getValue() != null ? param.getValue() : param.getDefaultValue();
            if (effectiveValue != null) {
                cache.put(param.getKey(), effectiveValue);
            }
        }
    }

    public List<ConfigParam> getAll() {
        return configParamRepository.listAll();
    }

    public List<ConfigParam> getByKeys(List<String> keys) {
        return configParamRepository.findByKeys(keys);
    }

    /**
     * Deletes a config param by key. Used for cleanup of temporary entries.
     */
    @Transactional
    public void deleteByKey(String key) {
        configParamRepository.findByKey(key).ifPresent(param -> {
            configParamRepository.delete(param);
            cache.remove(key);
        });
    }
}
