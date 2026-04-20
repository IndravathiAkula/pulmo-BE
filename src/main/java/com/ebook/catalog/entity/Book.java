package com.ebook.catalog.entity;

import com.ebook.catalog.enums.BookStatus;
import com.ebook.user.entity.User;
import com.ebook.common.entity.BaseEntity;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(name = "m_books", indexes = {
        @Index(name = "idx_book_is_published", columnList = "is_published"),
        @Index(name = "idx_book_status", columnList = "status")
})
public class Book extends BaseEntity {

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "price", nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(name = "discount", precision = 10, scale = 2)
    private BigDecimal discount = BigDecimal.ZERO;

    @Column(name = "keywords")
    private String keywords;

    @Column(name = "published_date")
    private Instant publishedDate;

    @Column(name = "pages")
    private Integer pages;

    @Column(name = "cover_url")
    private String coverUrl;

    @Column(name = "preview_url")
    private String previewUrl;

    @JsonIgnore
    @Column(name = "file_key")
    private String fileKey;

    @Column(name = "version_number")
    private String versionNumber;

    @Column(name = "is_published", nullable = false)
    private boolean isPublished = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private BookStatus status = BookStatus.PENDING;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id", nullable = false)
    private User author;

    @Version
    @Column(name = "version")
    private Long version;

}
