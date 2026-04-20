package com.ebook.commerce.entity;

import com.ebook.user.entity.User;
import com.ebook.catalog.entity.Book;
import com.ebook.common.entity.BaseEntity;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(name = "t_cart_items", uniqueConstraints = {
        @UniqueConstraint(name = "uk_cart_user_book", columnNames = { "user_id", "book_id" })
})
public class CartItem extends BaseEntity {

    @Column(name = "added_at", nullable = false)
    private Instant addedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "book_id", nullable = false)
    private Book book;

}
