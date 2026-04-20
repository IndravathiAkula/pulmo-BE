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
import com.ebook.user.repository.UserBookRepository;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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

    public PaymentService(PaymentHistoryRepository paymentHistoryRepository,
                          PaymentTransactionRepository paymentTransactionRepository,
                          UserBookRepository userBookRepository,
                          BookRepository bookRepository,
                          UserRepository userRepository,
                          CartItemRepository cartItemRepository) {
        this.paymentHistoryRepository = paymentHistoryRepository;
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.userBookRepository = userBookRepository;
        this.bookRepository = bookRepository;
        this.userRepository = userRepository;
        this.cartItemRepository = cartItemRepository;
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

        // Idempotency replay — return the existing payment for the same user + key
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var existing = paymentHistoryRepository.findByUserAndIdempotencyKey(userId, idempotencyKey);
            if (existing.isPresent()) {
                PaymentHistory prior = existing.get();
                return toResponse(prior, loadItemsForPayment(prior.getId()));
            }
        }

        List<Book> books = new ArrayList<>();
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
            books.add(book);
            total = total.add(effectivePrice(book));
        }

        PaymentHistory payment = new PaymentHistory();
        payment.setUser(user);
        payment.setAmount(total);
        payment.setPaymentMethod(PAYMENT_METHOD);
        payment.setStatus(PaymentStatus.SUCCESS);
        payment.setExternalPaymentId(idempotencyKey != null && !idempotencyKey.isBlank()
                ? idempotencyKey
                : UUID.randomUUID().toString());
        paymentHistoryRepository.save(payment);

        List<PurchasedBookResponse> items = new ArrayList<>();
        for (Book book : books) {
            BigDecimal bookDiscount = book.getDiscount() != null ? book.getDiscount() : BigDecimal.ZERO;
            BigDecimal bookEffective = book.getPrice().subtract(bookDiscount);
            if (bookEffective.compareTo(BigDecimal.ZERO) < 0) bookEffective = BigDecimal.ZERO;

            // Line-item: captures what the user paid at the moment of purchase.
            PaymentTransaction tx = new PaymentTransaction();
            tx.setPaymentHistory(payment);
            tx.setBook(book);
            tx.setPrice(book.getPrice());
            tx.setDiscount(bookDiscount);
            tx.setEffectivePrice(bookEffective);
            paymentTransactionRepository.save(tx);

            // Entitlement: grants reader access. Preserves per-user state (progress, last-read).
            UserBook userBook = new UserBook();
            userBook.setUser(user);
            userBook.setBook(book);
            userBook.setAccessGrantedAt(Instant.now());
            userBook.setPrice(book.getPrice());
            userBook.setDiscount(bookDiscount);
            userBook.setPaymentId(payment.getExternalPaymentId());
            userBook.setPaymentHistory(payment);
            userBookRepository.save(userBook);

            cartItemRepository.deleteByUserAndBook(userId, book.getId());
            items.add(toPurchasedBookResponse(userBook));
        }

        LOG.infof("Payment %s completed for user %s — amount %s (ip=%s)",
                payment.getId(), userId, total, ipAddress);

        return toResponse(payment, items);
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
        return PagedResponse.of(payments, total, req,
                p -> toResponse(p, loadItemsForPayment(p.getId())));
    }

    @Transactional
    public PaymentResponse getUserPayment(UUID userId, UUID paymentId) {
        PaymentHistory payment = paymentHistoryRepository.findByIdOptional(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
        if (!payment.getUser().getId().equals(userId)) {
            throw new ForbiddenException("You do not own this payment");
        }
        return toResponse(payment, loadItemsForPayment(paymentId));
    }

    @Transactional
    public PagedResponse<PurchasedBookResponse> getPurchasedBooks(UUID userId, PageRequest req) {
        Sort sort = buildSort(req);
        long total = userBookRepository.count("user.id", userId);
        List<UserBook> userBooks = userBookRepository
                .find("user.id = ?1", sort, userId)
                .page(Page.of(req.getPage(), req.getSize()))
                .list();
        return PagedResponse.of(userBooks, total, req, this::toPurchasedBookResponse);
    }

    // ─────────────────────────── HELPERS ───────────────────────────

    private Sort buildSort(PageRequest req) {
        Sort.Direction direction = req.getSortDirection() == PageRequest.SortDirection.ASC
                ? Sort.Direction.Ascending
                : Sort.Direction.Descending;
        return Sort.by(req.getSortField(), direction);
    }

    private List<PurchasedBookResponse> loadItemsForPayment(UUID paymentId) {
        return paymentTransactionRepository.findByPaymentId(paymentId).stream()
                .map(this::toPurchasedBookResponse)
                .toList();
    }

    private PurchasedBookResponse toPurchasedBookResponse(PaymentTransaction tx) {
        Book book = tx.getBook();
        BigDecimal discount = tx.getDiscount() != null ? tx.getDiscount() : BigDecimal.ZERO;
        return PurchasedBookResponse.builder()
                .bookId(book.getId())
                .title(book.getTitle())
                .authorName(book.getAuthor().getEmail())
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
        Book book = userBook.getBook();
        BigDecimal discount = userBook.getDiscount() != null ? userBook.getDiscount() : BigDecimal.ZERO;
        BigDecimal effective = userBook.getPrice().subtract(discount);
        if (effective.compareTo(BigDecimal.ZERO) < 0) {
            effective = BigDecimal.ZERO;
        }
        return PurchasedBookResponse.builder()
                .bookId(book.getId())
                .title(book.getTitle())
                .authorName(book.getAuthor().getEmail())
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
}
