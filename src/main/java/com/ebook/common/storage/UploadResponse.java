package com.ebook.common.storage;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Response shape for {@code POST /uploads}. The {@code key} is the stable
 * storage identifier (e.g. {@code covers/abc-123.jpg}); clients build their
 * own absolute URL from a known backend origin + {@code /files/} route.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UploadResponse {
    private String key;
    private String contentType;
    private long sizeBytes;
    private String kind;
}
