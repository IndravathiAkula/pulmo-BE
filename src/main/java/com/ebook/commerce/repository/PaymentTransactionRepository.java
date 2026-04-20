package com.ebook.commerce.repository;

import com.ebook.commerce.entity.PaymentTransaction;
import com.ebook.common.repository.BaseRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class PaymentTransactionRepository extends BaseRepository<PaymentTransaction, UUID> {

    /** Line items for a given payment, with book + category + author joined for response mapping. */
    public List<PaymentTransaction> findByPaymentId(UUID paymentId) {
        return getEntityManager()
                .createQuery(
                        "SELECT t FROM PaymentTransaction t " +
                                "JOIN FETCH t.book b JOIN FETCH b.author JOIN FETCH b.category " +
                                "WHERE t.paymentHistory.id = :paymentId " +
                                "ORDER BY t.createdAt ASC",
                        PaymentTransaction.class)
                .setParameter("paymentId", paymentId)
                .getResultList();
    }
}
