package com.ebook.commerce.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentResponse {
    private UUID paymentId;
    private UUID userId;
    private BigDecimal amount;
    private String paymentMethod;
    private String externalPaymentId;
    private String status;
    private List<PurchasedBookResponse> items;
    private Instant createdAt;
}
