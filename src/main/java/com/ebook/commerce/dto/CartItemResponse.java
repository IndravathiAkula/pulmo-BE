package com.ebook.commerce.dto;

import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CartItemResponse {
    private UUID bookId;
    private String title;
    private String authorName;
    private String categoryName;
    private String coverUrl;
    private BigDecimal price;
    private BigDecimal discount;
    private BigDecimal effectivePrice;
    private Instant addedAt;
}
