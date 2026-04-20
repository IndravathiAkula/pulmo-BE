package com.ebook.commerce.dto;

import lombok.*;
import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckoutResponse {
    private UUID paymentId;
    private BigDecimal totalAmount;
    private int itemsPurchased;
    private String status;
}
