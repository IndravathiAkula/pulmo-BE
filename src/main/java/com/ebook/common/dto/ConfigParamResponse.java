package com.ebook.common.dto;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfigParamResponse {
    private String name;
    private String key;
    private String value;
    private String defaultValue;
    private String possibleValues;
    private String type;
}
