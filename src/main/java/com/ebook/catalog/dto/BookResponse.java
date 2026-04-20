package com.ebook.catalog.dto;

import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookResponse {
    private UUID id;
    private String title;
    private String description;
    private BigDecimal price;
    private BigDecimal discount;
    private String keywords;
    private Instant publishedDate;
    private Integer pages;
    private String coverUrl;
    private String previewUrl;
    private String bookUrl;
    private String versionNumber;
    private boolean isPublished;
    private String status;
    private String rejectionReason;
    private UUID categoryId;
    private String categoryName;
    private UUID authorId;
    private String authorName;
    private Instant createdAt;
    private Instant updatedAt;
}
