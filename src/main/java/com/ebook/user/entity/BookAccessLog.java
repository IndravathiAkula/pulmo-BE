package com.ebook.user.entity;

import com.ebook.auth.entity.Device;
import com.ebook.auth.entity.Session;
import com.ebook.catalog.entity.Book;
import com.ebook.common.entity.BaseEntity;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(name = "t_book_access_logs", indexes = {
        @Index(name = "idx_bal_user_id", columnList = "user_id"),
        @Index(name = "idx_bal_book_id", columnList = "book_id"),
        @Index(name = "idx_bal_access_time", columnList = "access_time")
})
public class BookAccessLog extends BaseEntity {

    @Column(name = "access_time", nullable = false)
    private Instant accessTime;

    @Column(name = "action", nullable = false)
    private String action;

    @Column(name = "ip_address")
    private String ipAddress;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "book_id", nullable = false)
    private Book book;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_id")
    private Device device;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    private Session session;

}
