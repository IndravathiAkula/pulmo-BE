package com.ebook.common.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(name = "m_config_params", indexes = {
        @Index(name = "idx_config_key", columnList = "config_key")
})
public class ConfigParam {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "config_key", nullable = false, unique = true)
    private String key;

    @Column(name = "config_value", columnDefinition = "TEXT")
    private String value;

    @Column(name = "default_value", columnDefinition = "TEXT")
    private String defaultValue;

    @Column(name = "possible_values")
    private String possibleValues;

    @Column(name = "type", length = 20, nullable = false)
    private String type;

    public ConfigParam() {
    }

    public ConfigParam(String name, String key, String value, String defaultValue, String type) {
        this.name = name;
        this.key = key;
        this.value = value;
        this.defaultValue = defaultValue;
        this.type = type;
    }
}
