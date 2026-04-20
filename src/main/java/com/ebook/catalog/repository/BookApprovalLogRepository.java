package com.ebook.catalog.repository;

import com.ebook.catalog.entity.BookApprovalLog;
import com.ebook.common.repository.BaseRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class BookApprovalLogRepository extends BaseRepository<BookApprovalLog, UUID> {

    public List<BookApprovalLog> findByBookId(UUID bookId) {
        return getEntityManager()
                .createQuery("SELECT l FROM BookApprovalLog l JOIN FETCH l.sender JOIN FETCH l.receiver " +
                        "WHERE l.book.id = :bookId ORDER BY l.createdAt ASC", BookApprovalLog.class)
                .setParameter("bookId", bookId)
                .getResultList();
    }
}
