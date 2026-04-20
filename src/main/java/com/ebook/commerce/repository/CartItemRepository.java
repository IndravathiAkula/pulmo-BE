package com.ebook.commerce.repository;

import com.ebook.commerce.entity.CartItem;
import com.ebook.common.repository.BaseRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class CartItemRepository extends BaseRepository<CartItem, UUID> {

    public List<CartItem> findByUserId(UUID userId) {
        return getEntityManager()
                .createQuery("SELECT c FROM CartItem c JOIN FETCH c.book b JOIN FETCH b.author JOIN FETCH b.category " +
                        "WHERE c.user.id = :userId ORDER BY c.addedAt DESC", CartItem.class)
                .setParameter("userId", userId)
                .getResultList();
    }

    public Optional<CartItem> findByUserAndBook(UUID userId, UUID bookId) {
        return find("user.id = ?1 AND book.id = ?2", userId, bookId).firstResultOptional();
    }

    @Transactional
    public long deleteByUserId(UUID userId) {
        return delete("user.id", userId);
    }

    @Transactional
    public long deleteByUserAndBook(UUID userId, UUID bookId) {
        return delete("user.id = ?1 AND book.id = ?2", userId, bookId);
    }
}
