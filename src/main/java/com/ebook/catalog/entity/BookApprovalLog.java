package com.ebook.catalog.entity;

import com.ebook.catalog.enums.BookApprovalAction;
import com.ebook.common.entity.BaseEntity;
import com.ebook.user.entity.User;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(name = "t_book_approval_logs", indexes = {
        @Index(name = "idx_bapl_book_id", columnList = "book_id"),
        @Index(name = "idx_bapl_sender_id", columnList = "sender_id"),
        @Index(name = "idx_bapl_created_at", columnList = "created_at")
})
public class BookApprovalLog extends BaseEntity {

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "book_id", nullable = false)
    private Book book;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_id", nullable = false)
    private User sender;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receiver_id", nullable = false)
    private User receiver;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 30)
    private BookApprovalAction action;

    @Column(name = "message", columnDefinition = "text")
    private String message;

}
