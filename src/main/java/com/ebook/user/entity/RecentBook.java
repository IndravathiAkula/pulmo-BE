package com.ebook.user.entity;

import com.ebook.catalog.entity.Book;
import com.ebook.common.entity.BaseEntity;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(name = "t_recent_books", uniqueConstraints = {
        @UniqueConstraint(name = "uk_recent_user_book", columnNames = { "user_id", "book_id" })
}, indexes = {
        @Index(name = "idx_recent_view_history", columnList = "user_id, viewed_at DESC")
})
public class RecentBook extends BaseEntity {

    @Column(name = "viewed_at", nullable = false)
    private Instant viewedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "book_id", nullable = false)
    private Book book;

}
