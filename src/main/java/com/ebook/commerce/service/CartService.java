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
import com.ebook.common.storage.FileKeyUtil;
import com.ebook.auth.repository.UserRepository;
import com.ebook.user.entity.User;
import com.ebook.user.entity.UserProfile;
import com.ebook.user.repository.UserBookRepository;
import com.ebook.user.repository.UserProfileRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class CartService {

    private static final Logger LOG = Logger.getLogger(CartService.class);

    private final CartItemRepository cartItemRepository;
    private final BookRepository bookRepository;
    private final UserRepository userRepository;
    private final UserBookRepository userBookRepository;
    private final UserProfileRepository userProfileRepository;
    private final PaymentService paymentService;

    public CartService(CartItemRepository cartItemRepository, BookRepository bookRepository,
                       UserRepository userRepository, UserBookRepository userBookRepository,
                       UserProfileRepository userProfileRepository,
                       PaymentService paymentService) {
        this.cartItemRepository = cartItemRepository;
        this.bookRepository = bookRepository;
        this.userRepository = userRepository;
        this.userBookRepository = userBookRepository;
        this.userProfileRepository = userProfileRepository;
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
        // Duplicate add: surface explicitly as 409 so the FE can show "already in your cart"
        // instead of silently pretending the click worked (P2 #34).
        if (cartItemRepository.findByUserAndBook(userId, bookId).isPresent()) {
            throw new ConflictException("This book is already in your cart");
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

        // Collect books passing availability filters so we can batch-load author names once.
        List<CartItem> validCartItems = new ArrayList<>();
        List<Book> validBooks = new ArrayList<>();
        for (CartItem item : items) {
            Book book = item.getBook();
            if (book.getStatus() != BookStatus.APPROVED || !book.isPublished()) continue;
            if (userBookRepository.existsByUserAndBook(userId, book.getId())) continue;
            validCartItems.add(item);
            validBooks.add(book);
        }

        Map<UUID, String> authorNames = batchAuthorNames(validBooks);

        List<CartItemResponse> validItems = new ArrayList<>();
        BigDecimal totalPrice = BigDecimal.ZERO;
        for (CartItem item : validCartItems) {
            CartItemResponse response = toCartItemResponse(item, authorNames);
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
        return toCartItemResponse(item, null);
    }

    private CartItemResponse toCartItemResponse(CartItem item, Map<UUID, String> authorNames) {
        Book book = item.getBook();
        BigDecimal effectivePrice = calculateEffectivePrice(book);
        User author = book.getAuthor();
        String authorName;
        if (authorNames != null && author != null) {
            authorName = authorNames.getOrDefault(author.getId(), author.getEmail());
        } else {
            authorName = resolveAuthorName(author);
        }

        return CartItemResponse.builder()
                .bookId(book.getId())
                .title(book.getTitle())
                .authorName(authorName)
                .categoryName(book.getCategory().getName())
                .coverUrl(FileKeyUtil.toKey(book.getCoverUrl()))
                .price(book.getPrice())
                .discount(book.getDiscount() != null ? book.getDiscount() : BigDecimal.ZERO)
                .effectivePrice(effectivePrice)
                .addedAt(item.getAddedAt())
                .build();
    }

    private String resolveAuthorName(User author) {
        if (author == null) return null;
        return userProfileRepository.findByUserId(author.getId())
                .map(this::fullName)
                .filter(name -> !name.isBlank())
                .orElse(author.getEmail());
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
