package com.ebook.commerce.service;

import com.ebook.catalog.entity.Book;
import com.ebook.catalog.enums.BookStatus;
import com.ebook.catalog.repository.BookRepository;
import com.ebook.commerce.dto.*;
import com.ebook.commerce.entity.CartItem;
import com.ebook.commerce.repository.CartItemRepository;
import com.ebook.common.exception.ConflictException;
import com.ebook.common.exception.ResourceNotFoundException;
import com.ebook.common.exception.ValidationException;
import com.ebook.auth.repository.UserRepository;
import com.ebook.user.entity.User;
import com.ebook.user.repository.UserBookRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class CartService {

    private static final Logger LOG = Logger.getLogger(CartService.class);

    private final CartItemRepository cartItemRepository;
    private final BookRepository bookRepository;
    private final UserRepository userRepository;
    private final UserBookRepository userBookRepository;
    private final PaymentService paymentService;

    public CartService(CartItemRepository cartItemRepository, BookRepository bookRepository,
                       UserRepository userRepository, UserBookRepository userBookRepository,
                       PaymentService paymentService) {
        this.cartItemRepository = cartItemRepository;
        this.bookRepository = bookRepository;
        this.userRepository = userRepository;
        this.userBookRepository = userBookRepository;
        this.paymentService = paymentService;
    }

    // ═══════════════════════════ ADD TO CART ═══════════════════════════

    @Transactional
    public CartItemResponse addToCart(UUID userId, UUID bookId) {
        User user = findUserOrThrow(userId);
        Book book = bookRepository.findByIdOptional(bookId)
                .orElseThrow(() -> new ResourceNotFoundException("Book not found"));

        if (book.getStatus() != BookStatus.APPROVED || !book.isPublished()) {
            throw new ValidationException("Book is not available for purchase");
        }
        if (book.getAuthor().getId().equals(userId)) {
            throw new ValidationException("You cannot add your own book to cart");
        }
        if (userBookRepository.existsByUserAndBook(userId, bookId)) {
            // Clean up stale cart entry if it exists
            cartItemRepository.deleteByUserAndBook(userId, bookId);
            throw new ConflictException("You already own this book");
        }
        CartItem existing = cartItemRepository.findByUserAndBook(userId, bookId).orElse(null);
        if (existing != null) {
            return toCartItemResponse(existing);
        }

        CartItem cartItem = new CartItem();
        cartItem.setUser(user);
        cartItem.setBook(book);
        cartItem.setAddedAt(Instant.now());
        cartItemRepository.save(cartItem);

        LOG.infof("Book %s added to cart for user %s", bookId, userId);
        return toCartItemResponse(cartItem);
    }

    // ═══════════════════════════ GET CART ═══════════════════════════

    @Transactional
    public CartResponse getCart(UUID userId) {
        List<CartItem> items = cartItemRepository.findByUserId(userId);

        List<CartItemResponse> validItems = new ArrayList<>();
        BigDecimal totalPrice = BigDecimal.ZERO;

        for (CartItem item : items) {
            Book book = item.getBook();
            // Skip items where the book is no longer available or already owned
            if (book.getStatus() != BookStatus.APPROVED || !book.isPublished()) continue;
            if (userBookRepository.existsByUserAndBook(userId, book.getId())) continue;

            CartItemResponse response = toCartItemResponse(item);
            validItems.add(response);
            totalPrice = totalPrice.add(response.getEffectivePrice());
        }

        return CartResponse.builder()
                .items(validItems)
                .totalItems(validItems.size())
                .totalPrice(totalPrice)
                .build();
    }

    @Transactional
    public void removeFromCart(UUID userId, UUID bookId) {
        long deleted = cartItemRepository.deleteByUserAndBook(userId, bookId);
        if (deleted == 0) {
            throw new ResourceNotFoundException("Book not found in your cart");
        }
        LOG.infof("Book %s removed from cart for user %s", bookId, userId);
    }

    @Transactional
    public void clearCart(UUID userId) {
        long deleted = cartItemRepository.deleteByUserId(userId);
        LOG.infof("Cart cleared for user %s — %d items removed", userId, deleted);
    }

    // ═══════════════════════════ CHECKOUT ═══════════════════════════

    /**
     * One-shot mock checkout. Reads the cart, filters out stale items (unpublished, already owned,
     * author's own), cleans them up, and delegates valid items to {@link PaymentService#checkout}.
     */
    @Transactional
    public CheckoutResponse checkout(UUID userId, String idempotencyKey, String ipAddress) {
        List<CartItem> cartItems = cartItemRepository.findByUserId(userId);
        if (cartItems.isEmpty()) {
            throw new ValidationException("Your cart is empty");
        }

        // Filter: same rules as getCart() — skip stale items and clean them up
        List<UUID> validBookIds = new ArrayList<>();
        List<CartItem> staleItems = new ArrayList<>();

        for (CartItem item : cartItems) {
            Book book = item.getBook();
            if (book.getStatus() != BookStatus.APPROVED || !book.isPublished()) {
                staleItems.add(item);
            } else if (userBookRepository.existsByUserAndBook(userId, book.getId())) {
                staleItems.add(item);
            } else if (book.getAuthor().getId().equals(userId)) {
                staleItems.add(item);
            } else {
                validBookIds.add(book.getId());
            }
        }

        // Remove stale items from cart
        for (CartItem stale : staleItems) {
            cartItemRepository.deleteByUserAndBook(userId, stale.getBook().getId());
        }
        if (!staleItems.isEmpty()) {
            LOG.infof("Cleaned %d stale cart items for user %s during checkout", staleItems.size(), userId);
        }

        if (validBookIds.isEmpty()) {
            throw new ValidationException("Your cart has no purchasable items");
        }

        CheckoutRequest request = new CheckoutRequest(validBookIds);
        PaymentResponse payment = paymentService.checkout(userId, request, idempotencyKey, ipAddress);

        return CheckoutResponse.builder()
                .paymentId(payment.getPaymentId())
                .totalAmount(payment.getAmount())
                .itemsPurchased(payment.getItems() == null ? 0 : payment.getItems().size())
                .status(payment.getStatus())
                .build();
    }

    // ═══════════════════════════ HELPERS ═══════════════════════════

    private User findUserOrThrow(UUID userId) {
        return userRepository.findByIdOptional(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private BigDecimal calculateEffectivePrice(Book book) {
        BigDecimal price = book.getPrice();
        BigDecimal discount = book.getDiscount() != null ? book.getDiscount() : BigDecimal.ZERO;
        BigDecimal effective = price.subtract(discount);
        return effective.compareTo(BigDecimal.ZERO) > 0 ? effective : BigDecimal.ZERO;
    }

    private CartItemResponse toCartItemResponse(CartItem item) {
        Book book = item.getBook();
        BigDecimal effectivePrice = calculateEffectivePrice(book);

        return CartItemResponse.builder()
                .bookId(book.getId())
                .title(book.getTitle())
                .authorName(book.getAuthor().getEmail())
                .categoryName(book.getCategory().getName())
                .coverUrl(book.getCoverUrl())
                .price(book.getPrice())
                .discount(book.getDiscount() != null ? book.getDiscount() : BigDecimal.ZERO)
                .effectivePrice(effectivePrice)
                .addedAt(item.getAddedAt())
                .build();
    }
}
