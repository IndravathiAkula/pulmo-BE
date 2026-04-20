package com.ebook.commerce.repository;

import com.ebook.commerce.entity.PaymentHistory;
import com.ebook.common.repository.BaseRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class PaymentHistoryRepository extends BaseRepository<PaymentHistory, UUID> {

    public static final Set<String> SORTABLE_FIELDS = Set.of("createdAt", "amount", "status");
    public static final String DEFAULT_SORT_FIELD = "createdAt";

    public Optional<PaymentHistory> findByUserAndIdempotencyKey(UUID userId, String idempotencyKey) {
        return find("user.id = ?1 AND externalPaymentId = ?2", userId, idempotencyKey)
                .firstResultOptional();
    }
}
