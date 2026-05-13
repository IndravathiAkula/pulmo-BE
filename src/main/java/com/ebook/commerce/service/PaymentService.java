package com.ebook.commerce.service;

import com.ebook.auth.repository.UserRepository;
import com.ebook.catalog.entity.Book;
import com.ebook.catalog.enums.BookStatus;
import com.ebook.catalog.repository.BookRepository;
import com.ebook.commerce.dto.CheckoutRequest;
import com.ebook.commerce.dto.PaymentResponse;
import com.ebook.commerce.dto.PurchasedBookResponse;
import com.ebook.commerce.entity.PaymentHistory;
import com.ebook.commerce.entity.PaymentTransaction;
import com.ebook.commerce.enums.PaymentStatus;
import com.ebook.commerce.repository.CartItemRepository;
import com.ebook.commerce.repository.PaymentHistoryRepository;
import com.ebook.commerce.repository.PaymentTransactionRepository;
import com.ebook.common.dto.PageRequest;
import com.ebook.common.dto.PagedResponse;
import com.ebook.common.exception.ConflictException;
import com.ebook.common.exception.ForbiddenException;
import com.ebook.common.exception.ResourceNotFoundException;
import com.ebook.common.exception.ValidationException;
import com.ebook.user.entity.User;
import com.ebook.user.entity.UserBook;
import com.ebook.user.entity.UserProfile;
import com.ebook.user.repository.UserBookRepository;
import com.ebook.user.repository.UserProfileRepository;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Mock payment orchestration. Creates a PaymentHistory + one UserBook per purchased title,
 * clears cart entries, and supports Idempotency-Key replay.
 */
@ApplicationScoped
public class PaymentService {

    private static final Logger LOG = Logger.getLogger(PaymentService.class);
    private static final String PAYMENT_METHOD = "MOCK";

    private final PaymentHistoryRepository paymentHistoryRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final UserBookRepository userBookRepository;
    private final BookRepository bookRepository;
    private final UserRepository userRepository;
    private final CartItemRepository cartItemRepository;
    private final UserProfileRepository userProfileRepository;

    public PaymentService(PaymentHistoryRepository paymentHistoryRepository,
                          PaymentTransactionRepository paymentTransactionRepository,
                          UserBookRepository userBookRepository,
                          BookRepository bookRepository,
                          UserRepository userRepository,
                          CartItemRepository cartItemRepository,
                          UserProfileRepository userProfileRepository) {
        this.paymentHistoryRepository = paymentHistoryRepository;
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.userBookRepository = userBookRepository;
        this.bookRepository = bookRepository;
        this.userRepository = userRepository;
        this.cartItemRepository = cartItemRepository;
        this.userProfileRepository = userProfileRepository;
    }

    // ═══════════════════════════ CHECKOUT ═══════════════════════════

    @Transactional
    public PaymentResponse checkout(UUID userId, CheckoutRequest request,
                                    String idempotencyKey, String ipAddress) {
        if (request == null || request.getBookIds() == null || request.getBookIds().isEmpty()) {
            throw new ValidationException("No books selected for checkout");
        }

        User user = userRepository.findByIdOptional(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        // deduplicate even when the caller forgets to send Idempotency-Key. A deterministic
        // fallback key derived from (userId + sorted bookIds) means two concurrent submissions of
        // the same cart collapse onto the same payment. Any intentional repeat purchase is already
        // blocked by the "You already own" check below, so this never rejects a legit 2nd purchase.
        String effectiveIdempotencyKey = (idempotencyKey != null && !idempotencyKey.isBlank())
                ? idempotencyKey
                : deriveIdempotencyKey(userId, request.getBookIds());

        // Idempotency replay — return the existing payment for the same user + key
        var existing = paymentHistoryRepository.findByUserAndIdempotencyKey(userId, effectiveIdempotencyKey);
        if (existing.isPresent()) {
            PaymentHistory prior = existing.get();
            return toResponse(prior, loadItemsForPayment(prior.getId()));
        }

        // — snapshot price + discount at fetch time into a dedicated holder so every later
        // read within this TX uses the exact same numbers (defense in depth against a concurrent
        // admin update leaking into the ledger).
        List<Book> books = new ArrayList<>();
        Map<UUID, BigDecimal[]> priceSnapshots = new HashMap<>(); // bookId → {price, discount, effective}
        BigDecimal total = BigDecimal.ZERO;
        for (UUID bookId : request.getBookIds()) {
            Book book = bookRepository.findByIdOptional(bookId)
                    .orElseThrow(() -> new ResourceNotFoundException("Book not found: " + bookId));
            if (book.getStatus() != BookStatus.APPROVED || !book.isPublished()) {
                throw new ValidationException("Book is not available: " + book.getTitle());
            }
            if (book.getAuthor().getId().equals(userId)) {
                throw new ValidationException("You cannot purchase your own book: " + book.getTitle());
            }
            if (userBookRepository.existsByUserAndBook(userId, bookId)) {
                throw new ConflictException("You already own: " + book.getTitle());
            }
            BigDecimal snapPrice = book.getPrice();
            BigDecimal snapDiscount = book.getDiscount() != null ? book.getDiscount() : BigDecimal.ZERO;
            BigDecimal snapEffective = snapPrice.subtract(snapDiscount);
            if (snapEffective.compareTo(BigDecimal.ZERO) < 0) snapEffective = BigDecimal.ZERO;
            priceSnapshots.put(bookId, new BigDecimal[]{snapPrice, snapDiscount, snapEffective});
            books.add(book);
            total = total.add(snapEffective);
        }

        PaymentHistory payment = new PaymentHistory();
        payment.setUser(user);
        payment.setAmount(total);
        payment.setPaymentMethod(PAYMENT_METHOD);
        payment.setStatus(PaymentStatus.SUCCESS);
        payment.setExternalPaymentId(effectiveIdempotencyKey);
        try {
            paymentHistoryRepository.save(payment);
            paymentHistoryRepository.flush();
        } catch (jakarta.persistence.PersistenceException e) {
            // Concurrent checkout with the same (user, idempotency-key) lost the race against
            // the new uk_payment_user_idempotency constraint. The current transaction is now
            // rollback-only, so we can't re-fetch here — tell the client to retry. Their next
            // attempt hits the replay lookup at the top of this method and returns the
            // winning payment idempotently.
            if (e.getCause() instanceof org.hibernate.exception.ConstraintViolationException) {
                LOG.warnf("Concurrent checkout detected for user %s, key %s — client should retry",
                        userId, effectiveIdempotencyKey);
                throw new ConflictException(
                        "A concurrent checkout is in progress for this cart. Please retry.");
            }
            throw e;
        }

        Map<UUID, String> checkoutAuthorNames = batchAuthorNames(books);
        List<PurchasedBookResponse> items = new ArrayList<>();
        BigDecimal sumOfLineItems = BigDecimal.ZERO;
        for (Book book : books) {
            BigDecimal[] snap = priceSnapshots.get(book.getId());
            BigDecimal bookPrice = snap[0];
            BigDecimal bookDiscount = snap[1];
            BigDecimal bookEffective = snap[2];

            // Line-item: captures what the user paid at the moment of purchase.
            PaymentTransaction tx = new PaymentTransaction();
            tx.setPaymentHistory(payment);
            tx.setBook(book);
            tx.setPrice(bookPrice);
            tx.setDiscount(bookDiscount);
            tx.setEffectivePrice(bookEffective);
            paymentTransactionRepository.save(tx);

            // Entitlement: grants reader access. Preserves per-user state (progress, last-read).
            UserBook userBook = new UserBook();
            userBook.setUser(user);
            userBook.setBook(book);
            userBook.setAccessGrantedAt(Instant.now());
            userBook.setPrice(bookPrice);
            userBook.setDiscount(bookDiscount);
            userBook.setPaymentId(payment.getExternalPaymentId());
            userBook.setPaymentHistory(payment);
            userBookRepository.save(userBook);

            cartItemRepository.deleteByUserAndBook(userId, book.getId());
            sumOfLineItems = sumOfLineItems.add(bookEffective);
            items.add(toPurchasedBookResponse(userBook, checkoutAuthorNames));
        }

        // reconciliation — sum-of-line-items must equal payment.amount. Mismatch means
        // something mutated a snapshot mid-loop; refuse to commit rather than ship a bad ledger.
        if (sumOfLineItems.compareTo(total) != 0) {
            throw new ValidationException(
                    "Checkout ledger mismatch: line-items sum=" + sumOfLineItems + " vs payment total=" + total);
        }

        LOG.infof("Payment %s completed for user %s — amount %s (ip=%s)",
                payment.getId(), userId, total, ipAddress);

        return toResponse(payment, items);
    }

    private String deriveIdempotencyKey(UUID userId, List<UUID> bookIds) {
        List<String> sorted = bookIds.stream().map(UUID::toString).sorted().toList();
        String raw = userId.toString() + ":" + String.join(",", sorted);
        return "auto-" + com.ebook.common.util.TokenHashUtil.sha256Hex(raw);
    }

    // ═══════════════════════════ QUERIES ═══════════════════════════

    @Transactional
    public PagedResponse<PaymentResponse> getUserPayments(UUID userId, PageRequest req) {
        Sort sort = buildSort(req);
        long total = paymentHistoryRepository.count("user.id", userId);
        List<PaymentHistory> payments = paymentHistoryRepository
                .find("user.id = ?1", sort, userId)
                .page(Page.of(req.getPage(), req.getSize()))
                .list();

        // Batch-load items for all payments in one query (P1 #18) and group by payment id.
        List<UUID> paymentIds = payments.stream().map(PaymentHistory::getId).toList();
        List<PaymentTransaction> allItems = paymentTransactionRepository.findByPaymentIds(paymentIds);
        Map<UUID, List<PaymentTransaction>> itemsByPayment = new LinkedHashMap<>();
        for (PaymentTransaction tx : allItems) {
            itemsByPayment.computeIfAbsent(tx.getPaymentHistory().getId(), k -> new ArrayList<>()).add(tx);
        }
        // Batch-resolve author names for every book referenced across the page (P1 #17).
        Set<Book> booksOnPage = new HashSet<>();
        for (PaymentTransaction tx : allItems) booksOnPage.add(tx.getBook());
        Map<UUID, String> authorNames = batchAuthorNames(booksOnPage);

        return PagedResponse.of(payments, total, req,
                p -> toResponse(p, mapTransactions(itemsByPayment.getOrDefault(p.getId(), List.of()), authorNames)));
    }

    @Transactional
    public PaymentResponse getUserPayment(UUID userId, UUID paymentId) {
        PaymentHistory payment = paymentHistoryRepository.findByIdOptional(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
        if (!payment.getUser().getId().equals(userId)) {
            throw new ForbiddenException("You do not own this payment");
        }
        List<PaymentTransaction> items = paymentTransactionRepository.findByPaymentId(paymentId);
        Set<Book> books = new HashSet<>();
        for (PaymentTransaction tx : items) books.add(tx.getBook());
        Map<UUID, String> authorNames = batchAuthorNames(books);
        return toResponse(payment, mapTransactions(items, authorNames));
    }

    @Transactional
    public PagedResponse<PurchasedBookResponse> getPurchasedBooks(UUID userId, PageRequest req) {
        Sort sort = buildSort(req);
        long total = userBookRepository.count("user.id", userId);
        List<UserBook> userBooks = userBookRepository
                .find("user.id = ?1", sort, userId)
                .page(Page.of(req.getPage(), req.getSize()))
                .list();
        Set<Book> books = new HashSet<>();
        for (UserBook ub : userBooks) books.add(ub.getBook());
        Map<UUID, String> authorNames = batchAuthorNames(books);
        return PagedResponse.of(userBooks, total, req, ub -> toPurchasedBookResponse(ub, authorNames));
    }

    // ─────────────────────────── HELPERS ───────────────────────────

    private Sort buildSort(PageRequest req) {
        Sort.Direction direction = req.getSortDirection() == PageRequest.SortDirection.ASC
                ? Sort.Direction.Ascending
                : Sort.Direction.Descending;
        return Sort.by(req.getSortField(), direction);
    }

    private List<PurchasedBookResponse> loadItemsForPayment(UUID paymentId) {
        List<PaymentTransaction> items = paymentTransactionRepository.findByPaymentId(paymentId);
        Set<Book> books = new HashSet<>();
        for (PaymentTransaction tx : items) books.add(tx.getBook());
        Map<UUID, String> authorNames = batchAuthorNames(books);
        return mapTransactions(items, authorNames);
    }

    private List<PurchasedBookResponse> mapTransactions(List<PaymentTransaction> items,
                                                        Map<UUID, String> authorNames) {
        List<PurchasedBookResponse> out = new ArrayList<>(items.size());
        for (PaymentTransaction tx : items) {
            out.add(toPurchasedBookResponse(tx, authorNames));
        }
        return out;
    }

    private PurchasedBookResponse toPurchasedBookResponse(PaymentTransaction tx,
                                                          Map<UUID, String> authorNames) {
        Book book = tx.getBook();
        BigDecimal discount = tx.getDiscount() != null ? tx.getDiscount() : BigDecimal.ZERO;
        return PurchasedBookResponse.builder()
                .bookId(book.getId())
                .title(book.getTitle())
                .authorName(authorName(book, authorNames))
                .categoryName(book.getCategory().getName())
                .coverUrl(book.getCoverUrl())
                .price(tx.getPrice())
                .discount(discount)
                .effectivePrice(tx.getEffectivePrice())
                .purchasedAt(tx.getCreatedAt())
                // progress/lastReadAt live on UserBook — not part of the billing line item
                .progressPercentage(0)
                .build();
    }

    private BigDecimal effectivePrice(Book book) {
        BigDecimal price = book.getPrice();
        BigDecimal discount = book.getDiscount() != null ? book.getDiscount() : BigDecimal.ZERO;
        BigDecimal effective = price.subtract(discount);
        return effective.compareTo(BigDecimal.ZERO) > 0 ? effective : BigDecimal.ZERO;
    }

    private PaymentResponse toResponse(PaymentHistory payment, List<PurchasedBookResponse> items) {
        return PaymentResponse.builder()
                .paymentId(payment.getId())
                .userId(payment.getUser().getId())
                .amount(payment.getAmount())
                .paymentMethod(payment.getPaymentMethod())
                .externalPaymentId(payment.getExternalPaymentId())
                .status(payment.getStatus().name())
                .items(items)
                .createdAt(payment.getCreatedAt())
                .build();
    }

    private PurchasedBookResponse toPurchasedBookResponse(UserBook userBook) {
        return toPurchasedBookResponse(userBook, batchAuthorNames(List.of(userBook.getBook())));
    }

    private PurchasedBookResponse toPurchasedBookResponse(UserBook userBook,
                                                          Map<UUID, String> authorNames) {
        Book book = userBook.getBook();
        BigDecimal discount = userBook.getDiscount() != null ? userBook.getDiscount() : BigDecimal.ZERO;
        BigDecimal effective = userBook.getPrice().subtract(discount);
        if (effective.compareTo(BigDecimal.ZERO) < 0) {
            effective = BigDecimal.ZERO;
        }
        return PurchasedBookResponse.builder()
                .bookId(book.getId())
                .title(book.getTitle())
                .authorName(authorName(book, authorNames))
                .categoryName(book.getCategory().getName())
                .coverUrl(book.getCoverUrl())
                .price(userBook.getPrice())
                .discount(discount)
                .effectivePrice(effective)
                .purchasedAt(userBook.getAccessGrantedAt())
                .lastReadAt(userBook.getLastReadAt())
                .progressPercentage(userBook.getProgressPercentage())
                .build();
    }

    private String authorName(Book book, Map<UUID, String> authorNames) {
        User author = book.getAuthor();
        if (author == null) return null;
        if (authorNames != null) {
            String cached = authorNames.get(author.getId());
            if (cached != null) return cached;
        }
        return author.getEmail();
    }

    private Map<UUID, String> batchAuthorNames(Collection<Book> books) {
        if (books == null || books.isEmpty()) return Map.of();
        Set<UUID> authorIds = new HashSet<>();
        Map<UUID, String> fallbackEmails = new HashMap<>();
        for (Book b : books) {
            User author = b.getAuthor();
            if (author == null) continue;
            authorIds.add(author.getId());
            fallbackEmails.putIfAbsent(author.getId(), author.getEmail());
        }
        Map<UUID, String> byId = new HashMap<>();
        for (UserProfile p : userProfileRepository.findByUserIds(authorIds)) {
            String name = fullName(p);
            if (!name.isBlank()) {
                byId.put(p.getUser().getId(), name);
            }
        }
        Map<UUID, String> result = new HashMap<>();
        for (UUID id : authorIds) {
            result.put(id, byId.getOrDefault(id, fallbackEmails.get(id)));
        }
        return result;
    }

    private String fullName(UserProfile profile) {
        String first = profile.getFirstName() == null ? "" : profile.getFirstName().trim();
        String last = profile.getLastName() == null ? "" : profile.getLastName().trim();
        return (first + " " + last).trim();
    }
}
