package com.ebook.commerce.entity;

import com.ebook.catalog.entity.Book;
import com.ebook.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Line-item of a {@link PaymentHistory}. A payment of three books creates one
 * {@code PaymentHistory} row and three {@code PaymentTransaction} rows.
 *
 * <p>Price and discount are snapshotted at purchase time so refunds and
 * receipts reflect what the user actually paid, even if the book's price
 * later changes. Entitlement lives on {@code UserBook} — a refund can
 * revoke access without destroying this record.
 */
@Entity
@Getter
@Setter
@Table(name = "t_payment_transactions", indexes = {
        @Index(name = "idx_payment_tx_payment_id", columnList = "payment_history_id"),
        @Index(name = "idx_payment_tx_book_id", columnList = "book_id")
})
public class PaymentTransaction extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_history_id", nullable = false)
    private PaymentHistory paymentHistory;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "book_id", nullable = false)
    private Book book;

    @Column(name = "price", nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(name = "discount", precision = 10, scale = 2)
    private BigDecimal discount = BigDecimal.ZERO;

    @Column(name = "effective_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal effectivePrice;
}
