package com.ebook.catalog.repository;

import com.ebook.catalog.entity.Book;
import com.ebook.catalog.enums.BookStatus;
import com.ebook.common.dto.PageRequest;
import com.ebook.common.repository.BaseRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class BookRepository extends BaseRepository<Book, UUID> {

    public static final Set<String> SORTABLE_FIELDS =
            Set.of("createdAt", "title", "price", "publishedDate");
    public static final String DEFAULT_SORT_FIELD = "createdAt";

    /** Public: only approved + published books */
    public List<Book> findPublished() {
        return getEntityManager()
                .createQuery("SELECT b FROM Book b JOIN FETCH b.category JOIN FETCH b.author " +
                        "WHERE b.status = :status AND b.isPublished = true ORDER BY b.createdAt DESC", Book.class)
                .setParameter("status", BookStatus.APPROVED)
                .getResultList();
    }

    /** Public: published books by category */
    public List<Book> findPublishedByCategory(UUID categoryId) {
        return getEntityManager()
                .createQuery("SELECT b FROM Book b JOIN FETCH b.category JOIN FETCH b.author " +
                        "WHERE b.status = :status AND b.isPublished = true AND b.category.id = :categoryId " +
                        "ORDER BY b.createdAt DESC", Book.class)
                .setParameter("status", BookStatus.APPROVED)
                .setParameter("categoryId", categoryId)
                .getResultList();
    }

    /** Public: published books by author */
    public List<Book> findPublishedByAuthor(UUID authorId) {
        return getEntityManager()
                .createQuery("SELECT b FROM Book b JOIN FETCH b.category JOIN FETCH b.author " +
                        "WHERE b.status = :status AND b.isPublished = true AND b.author.id = :authorId " +
                        "ORDER BY b.createdAt DESC", Book.class)
                .setParameter("status", BookStatus.APPROVED)
                .setParameter("authorId", authorId)
                .getResultList();
    }

    /** Author: all their books (any status except DELETED) */
    public List<Book> findByAuthor(UUID authorId) {
        return getEntityManager()
                .createQuery("SELECT b FROM Book b JOIN FETCH b.category JOIN FETCH b.author " +
                        "WHERE b.author.id = :authorId AND b.status != :deleted ORDER BY b.createdAt DESC", Book.class)
                .setParameter("authorId", authorId)
                .setParameter("deleted", BookStatus.DELETED)
                .getResultList();
    }

    /** Admin: all books (any status) */
    public List<Book> findAllWithDetails() {
        return getEntityManager()
                .createQuery("SELECT b FROM Book b JOIN FETCH b.category JOIN FETCH b.author " +
                        "ORDER BY b.createdAt DESC", Book.class)
                .getResultList();
    }

    /** Admin: books pending approval */
    public List<Book> findPending() {
        return getEntityManager()
                .createQuery("SELECT b FROM Book b JOIN FETCH b.category JOIN FETCH b.author " +
                        "WHERE b.status = :status ORDER BY b.createdAt ASC", Book.class)
                .setParameter("status", BookStatus.PENDING)
                .getResultList();
    }

    // ─────────────────────────── PAGED VARIANTS ───────────────────────────

    public List<Book> findPublishedPage(PageRequest req) {
        return pagedQuery(
                "SELECT b FROM Book b JOIN FETCH b.category JOIN FETCH b.author " +
                        "WHERE b.status = :status AND b.isPublished = true " + req.orderByClause("b"),
                req)
                .setParameter("status", BookStatus.APPROVED)
                .getResultList();
    }

    public long countPublished() {
        return getEntityManager()
                .createQuery("SELECT COUNT(b) FROM Book b WHERE b.status = :status AND b.isPublished = true",
                        Long.class)
                .setParameter("status", BookStatus.APPROVED)
                .getSingleResult();
    }

    public List<Book> findPublishedByCategoryPage(UUID categoryId, PageRequest req) {
        return pagedQuery(
                "SELECT b FROM Book b JOIN FETCH b.category JOIN FETCH b.author " +
                        "WHERE b.status = :status AND b.isPublished = true AND b.category.id = :categoryId " +
                        req.orderByClause("b"),
                req)
                .setParameter("status", BookStatus.APPROVED)
                .setParameter("categoryId", categoryId)
                .getResultList();
    }

    public long countPublishedByCategory(UUID categoryId) {
        return getEntityManager()
                .createQuery("SELECT COUNT(b) FROM Book b " +
                        "WHERE b.status = :status AND b.isPublished = true AND b.category.id = :categoryId",
                        Long.class)
                .setParameter("status", BookStatus.APPROVED)
                .setParameter("categoryId", categoryId)
                .getSingleResult();
    }

    public List<Book> findPublishedByAuthorPage(UUID authorId, PageRequest req) {
        return pagedQuery(
                "SELECT b FROM Book b JOIN FETCH b.category JOIN FETCH b.author " +
                        "WHERE b.status = :status AND b.isPublished = true AND b.author.id = :authorId " +
                        req.orderByClause("b"),
                req)
                .setParameter("status", BookStatus.APPROVED)
                .setParameter("authorId", authorId)
                .getResultList();
    }

    public long countPublishedByAuthor(UUID authorId) {
        return getEntityManager()
                .createQuery("SELECT COUNT(b) FROM Book b " +
                        "WHERE b.status = :status AND b.isPublished = true AND b.author.id = :authorId",
                        Long.class)
                .setParameter("status", BookStatus.APPROVED)
                .setParameter("authorId", authorId)
                .getSingleResult();
    }

    public List<Book> findByAuthorPage(UUID authorId, PageRequest req) {
        return pagedQuery(
                "SELECT b FROM Book b JOIN FETCH b.category JOIN FETCH b.author " +
                        "WHERE b.author.id = :authorId AND b.status != :deleted " + req.orderByClause("b"),
                req)
                .setParameter("authorId", authorId)
                .setParameter("deleted", BookStatus.DELETED)
                .getResultList();
    }

    public long countByAuthor(UUID authorId) {
        return getEntityManager()
                .createQuery("SELECT COUNT(b) FROM Book b " +
                        "WHERE b.author.id = :authorId AND b.status != :deleted", Long.class)
                .setParameter("authorId", authorId)
                .setParameter("deleted", BookStatus.DELETED)
                .getSingleResult();
    }

    public List<Book> findAllWithDetailsPage(PageRequest req) {
        return pagedQuery(
                "SELECT b FROM Book b JOIN FETCH b.category JOIN FETCH b.author " + req.orderByClause("b"),
                req)
                .getResultList();
    }

    public long countAll() {
        return getEntityManager()
                .createQuery("SELECT COUNT(b) FROM Book b", Long.class)
                .getSingleResult();
    }

    public List<Book> findPendingPage(PageRequest req) {
        return pagedQuery(
                "SELECT b FROM Book b JOIN FETCH b.category JOIN FETCH b.author " +
                        "WHERE b.status = :status " + req.orderByClause("b"),
                req)
                .setParameter("status", BookStatus.PENDING)
                .getResultList();
    }

    public long countPending() {
        return getEntityManager()
                .createQuery("SELECT COUNT(b) FROM Book b WHERE b.status = :status", Long.class)
                .setParameter("status", BookStatus.PENDING)
                .getSingleResult();
    }

    private jakarta.persistence.TypedQuery<Book> pagedQuery(String jpql, PageRequest req) {
        return getEntityManager()
                .createQuery(jpql, Book.class)
                .setFirstResult(req.getPage() * req.getSize())
                .setMaxResults(req.getSize());
    }
}
