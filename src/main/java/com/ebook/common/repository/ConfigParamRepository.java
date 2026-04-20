package com.ebook.common.repository;

import com.ebook.common.entity.ConfigParam;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class ConfigParamRepository extends BaseRepository<ConfigParam, Long> {

    public Optional<ConfigParam> findByKey(String key) {
        return find("key", key).firstResultOptional();
    }

    public List<ConfigParam> findByKeys(List<String> keys) {
        return list("key IN ?1", keys);
    }
}
