package com.ebook.user.entity;

import com.ebook.catalog.entity.Book;
import com.ebook.commerce.entity.PaymentHistory;
import com.ebook.common.entity.BaseEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(name = "t_user_books", uniqueConstraints = {
        @UniqueConstraint(name = "uk_user_book", columnNames = { "user_id", "book_id" })
}, indexes = {
        @Index(name = "idx_user_book_user_id", columnList = "user_id"),
        @Index(name = "idx_user_book_book_id", columnList = "book_id")
})
public class UserBook extends BaseEntity {

    @Column(name = "access_granted_at", nullable = false)
    private Instant accessGrantedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "last_read_at")
    private Instant lastReadAt;

    @Column(name = "progress_percentage")
    private double progressPercentage = 0.0;

    @Column(name = "price", nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(name = "discount", precision = 10, scale = 2)
    private BigDecimal discount = BigDecimal.ZERO;

    @Column(name = "payment_id")
    private String paymentId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "book_id", nullable = false)
    private Book book;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_history_id")
    private PaymentHistory paymentHistory;
}
