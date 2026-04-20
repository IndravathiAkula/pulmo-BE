package com.ebook.catalog.service;

import com.ebook.auth.enums.UserType;
import com.ebook.catalog.dto.BookApprovalLogResponse;
import com.ebook.catalog.dto.BookResponse;
import com.ebook.catalog.dto.CreateBookRequest;
import com.ebook.catalog.dto.UpdateBookRequest;
import com.ebook.catalog.entity.Book;
import com.ebook.catalog.entity.BookApprovalLog;
import com.ebook.catalog.entity.Category;
import com.ebook.catalog.enums.BookApprovalAction;
import com.ebook.catalog.enums.BookStatus;
import com.ebook.catalog.repository.BookApprovalLogRepository;
import com.ebook.catalog.repository.BookRepository;
import com.ebook.catalog.repository.CategoryRepository;
import com.ebook.auth.repository.UserRepository;
import com.ebook.common.dto.PageRequest;
import com.ebook.common.dto.PagedResponse;
import com.ebook.common.exception.ForbiddenException;
import com.ebook.common.exception.ResourceNotFoundException;
import com.ebook.common.exception.ValidationException;
import com.ebook.common.service.EmailService;
import com.ebook.user.entity.User;
import com.ebook.user.entity.UserProfile;
import com.ebook.user.repository.UserProfileRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class BookService {

    private static final Logger LOG = Logger.getLogger(BookService.class);

    private final BookRepository bookRepository;
    private final CategoryRepository categoryRepository;
    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;
    private final EmailService emailService;
    private final BookApprovalLogRepository approvalLogRepository;

    @ConfigProperty(name = "storage.local.public-base-url", defaultValue = "/ebook/files")
    String publicFileBaseUrl;

    @ConfigProperty(name = "app.backend-url", defaultValue = "https://pulmo-be.onrender.com")
    String backendBaseUrl;

    public BookService(BookRepository bookRepository, CategoryRepository categoryRepository,
                       UserRepository userRepository, UserProfileRepository userProfileRepository,
                       EmailService emailService,
                       BookApprovalLogRepository approvalLogRepository) {
        this.bookRepository = bookRepository;
        this.categoryRepository = categoryRepository;
        this.userRepository = userRepository;
        this.userProfileRepository = userProfileRepository;
        this.emailService = emailService;
        this.approvalLogRepository = approvalLogRepository;
    }

    // ═══════════════════════════ AUTHOR OPERATIONS ═══════════════════════════

    @Transactional
    public BookResponse createBook(UUID authorId, CreateBookRequest request) {
        User author = findAuthorOrThrow(authorId);
        Category category = findActiveCategoryOrThrow(request.getCategoryId());

        Book book = new Book();
        book.setTitle(request.getTitle());
        book.setDescription(request.getDescription());
        book.setPrice(request.getPrice());
        book.setDiscount(request.getDiscount() != null ? request.getDiscount() : BigDecimal.ZERO);
        book.setKeywords(request.getKeywords());
        book.setPages(request.getPages());
        book.setCoverUrl(request.getCoverUrl());
        book.setPreviewUrl(request.getPreviewUrl());
        book.setVersionNumber(request.getVersionNumber());
        book.setFileKey(urlToFileKey(request.getBookUrl()));
        book.setCategory(category);
        book.setAuthor(author);
        book.setStatus(BookStatus.PENDING);
        book.setPublished(false);
        bookRepository.save(book);

        User admin = findAdminUser();
        logApproval(book, author, admin, BookApprovalAction.SUBMITTED, null);
        emailService.sendBookApprovalRequest(book.getTitle(), author.getEmail(), "created", book.getId().toString());

        LOG.infof("Book created (pending approval): %s by author %s", book.getId(), authorId);
        return toResponse(book);
    }

    @Transactional
    public BookResponse updateBook(UUID authorId, UUID bookId, UpdateBookRequest request) {
        Book book = findBookOwnedByAuthor(authorId, bookId);
        Category category = findActiveCategoryOrThrow(request.getCategoryId());

        BookStatus priorStatus = book.getStatus();
        boolean wasRejected = priorStatus == BookStatus.REJECTED;

        String newFileKey = urlToFileKey(request.getBookUrl());

        // Spec §4.5: substantive changes on an APPROVED book re-queue it for review; cosmetic ones don't.
        boolean substantiveChange =
                !safeEquals(book.getTitle(), request.getTitle())
                || !safeEquals(book.getDescription(), request.getDescription())
                || !safeEquals(book.getPrice(), request.getPrice())
                || !safeEquals(book.getPreviewUrl(), request.getPreviewUrl())
                || !safeEquals(book.getFileKey(), newFileKey)
                || !safeEquals(book.getCategory().getId(), request.getCategoryId());

        book.setTitle(request.getTitle());
        book.setDescription(request.getDescription());
        book.setPrice(request.getPrice());
        book.setDiscount(request.getDiscount() != null ? request.getDiscount() : BigDecimal.ZERO);
        book.setKeywords(request.getKeywords());
        book.setPages(request.getPages());
        book.setCoverUrl(request.getCoverUrl());
        book.setPreviewUrl(request.getPreviewUrl());
        book.setVersionNumber(request.getVersionNumber());
        book.setFileKey(newFileKey);
        book.setCategory(category);

        boolean requeue = wasRejected || priorStatus == BookStatus.PENDING || substantiveChange;
        if (requeue) {
            book.setStatus(BookStatus.PENDING);
            book.setPublished(false);
            book.setRejectionReason(null);
        }
        bookRepository.update(book);

        if (requeue) {
            User admin = findAdminUser();
            BookApprovalAction action = wasRejected ? BookApprovalAction.RESUBMITTED : BookApprovalAction.SUBMITTED;
            logApproval(book, book.getAuthor(), admin, action, request.getMessage());
            emailService.sendBookApprovalRequest(book.getTitle(), book.getAuthor().getEmail(), "updated", bookId.toString());
            LOG.infof("Book updated (pending re-approval): %s by author %s", bookId, authorId);
        } else {
            LOG.infof("Book cosmetic update (status unchanged): %s by author %s", bookId, authorId);
        }
        return toResponse(book);
    }

    private static boolean safeEquals(Object a, Object b) {
        if (a == null) return b == null;
        if (b == null) return false;
        if (a instanceof java.math.BigDecimal ba && b instanceof java.math.BigDecimal bb) {
            return ba.compareTo(bb) == 0;
        }
        return a.equals(b);
    }

    @Transactional
    public void deleteBook(UUID authorId, UUID bookId) {
        Book book = findBookOwnedByAuthor(authorId, bookId);

        book.setStatus(BookStatus.DELETED);
        book.setPublished(false);
        bookRepository.update(book);

        User admin = findAdminUser();
        logApproval(book, book.getAuthor(), admin, BookApprovalAction.DELETION_REQUESTED, null);
        emailService.sendBookApprovalRequest(book.getTitle(), book.getAuthor().getEmail(), "deletion requested", bookId.toString());

        LOG.infof("Book soft-deleted: %s by author %s", bookId, authorId);
    }

    @Transactional
    public List<BookResponse> getMyBooks(UUID authorId) {
        return bookRepository.findByAuthor(authorId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public BookResponse getMyBookById(UUID authorId, UUID bookId) {
        Book book = findBookOwnedByAuthor(authorId, bookId);
        return toResponse(book);
    }

    // ═══════════════════════════ ADMIN OPERATIONS ═══════════════════════════

    @Transactional
    public BookResponse approveBook(UUID adminId, UUID bookId) {
        Book book = findBookOrThrow(bookId);
        User admin = userRepository.findByIdOptional(adminId)
                .orElseThrow(() -> new ResourceNotFoundException("Admin not found"));

        if (book.getStatus() == BookStatus.DELETED) {
            throw new ValidationException("Cannot approve a deleted book");
        }
        if (book.getStatus() == BookStatus.APPROVED) {
            throw new ValidationException("Book is already approved");
        }

        book.setStatus(BookStatus.APPROVED);
        book.setPublished(true);
        book.setPublishedDate(Instant.now());
        book.setRejectionReason(null);
        bookRepository.update(book);

        logApproval(book, admin, book.getAuthor(), BookApprovalAction.APPROVED, null);

        LOG.infof("Book approved: %s by admin %s", bookId, adminId);
        return toResponse(book);
    }

    @Transactional
    public BookResponse rejectBook(UUID adminId, UUID bookId, String reason) {
        Book book = findBookOrThrow(bookId);
        User admin = userRepository.findByIdOptional(adminId)
                .orElseThrow(() -> new ResourceNotFoundException("Admin not found"));

        if (book.getStatus() == BookStatus.DELETED) {
            throw new ValidationException("Cannot reject a deleted book");
        }
        if (book.getStatus() == BookStatus.REJECTED) {
            throw new ValidationException("Book is already rejected");
        }

        book.setStatus(BookStatus.REJECTED);
        book.setPublished(false);
        book.setRejectionReason(reason);
        bookRepository.update(book);

        logApproval(book, admin, book.getAuthor(), BookApprovalAction.REJECTED, reason);

        LOG.infof("Book rejected: %s — reason: %s", bookId, reason);
        return toResponse(book);
    }

    @Transactional
    public List<BookResponse> getAllBooksForAdmin() {
        return bookRepository.findAllWithDetails().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public List<BookResponse> getPendingBooks() {
        return bookRepository.findPending().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public BookResponse getBookForAdmin(UUID bookId) {
        Book book = findBookOrThrow(bookId);
        return toResponse(book);
    }

    // ═══════════════════════════ APPROVAL HISTORY ═══════════════════════════

    @Transactional
    public List<BookApprovalLogResponse> getApprovalHistory(UUID bookId) {
        return approvalLogRepository.findByBookId(bookId).stream()
                .map(this::toLogResponse)
                .toList();
    }

    // ═══════════════════════════ PUBLIC OPERATIONS ═══════════════════════════

    @Transactional
    public List<BookResponse> getPublishedBooks() {
        return bookRepository.findPublished().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public List<BookResponse> getPublishedBooksByCategory(UUID categoryId) {
        return bookRepository.findPublishedByCategory(categoryId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public List<BookResponse> getPublishedBooksByAuthor(UUID authorId) {
        return bookRepository.findPublishedByAuthor(authorId).stream()
                .map(this::toResponse)
                .toList();
    }

    // ─────────────────────────── PAGED VARIANTS ───────────────────────────

    @Transactional
    public PagedResponse<BookResponse> getMyBooks(UUID authorId, PageRequest req) {
        return PagedResponse.of(
                bookRepository.findByAuthorPage(authorId, req),
                bookRepository.countByAuthor(authorId),
                req, this::toResponse);
    }

    @Transactional
    public PagedResponse<BookResponse> getAllBooksForAdmin(PageRequest req) {
        return PagedResponse.of(
                bookRepository.findAllWithDetailsPage(req),
                bookRepository.countAll(),
                req, this::toResponse);
    }

    @Transactional
    public PagedResponse<BookResponse> getPendingBooks(PageRequest req) {
        return PagedResponse.of(
                bookRepository.findPendingPage(req),
                bookRepository.countPending(),
                req, this::toResponse);
    }

    @Transactional
    public PagedResponse<BookResponse> getPublishedBooks(PageRequest req) {
        return PagedResponse.of(
                bookRepository.findPublishedPage(req),
                bookRepository.countPublished(),
                req, this::toResponse);
    }

    @Transactional
    public PagedResponse<BookResponse> getPublishedBooksByCategory(UUID categoryId, PageRequest req) {
        return PagedResponse.of(
                bookRepository.findPublishedByCategoryPage(categoryId, req),
                bookRepository.countPublishedByCategory(categoryId),
                req, this::toResponse);
    }

    @Transactional
    public PagedResponse<BookResponse> getPublishedBooksByAuthor(UUID authorId, PageRequest req) {
        return PagedResponse.of(
                bookRepository.findPublishedByAuthorPage(authorId, req),
                bookRepository.countPublishedByAuthor(authorId),
                req, this::toResponse);
    }

    @Transactional
    public BookResponse getPublishedBookById(UUID bookId) {
        Book book = findBookOrThrow(bookId);
        if (book.getStatus() != BookStatus.APPROVED || !book.isPublished()) {
            throw new ResourceNotFoundException("Book not found");
        }
        return toResponse(book);
    }

    // ═══════════════════════════ HELPERS ═══════════════════════════

    private void logApproval(Book book, User sender, User receiver, BookApprovalAction action, String message) {
        BookApprovalLog log = new BookApprovalLog();
        log.setBook(book);
        log.setSender(sender);
        log.setReceiver(receiver);
        log.setAction(action);
        log.setMessage(message);
        approvalLogRepository.save(log);
    }

    private User findAdminUser() {
        String adminEmail = "taskt600@gmail.com";
        return userRepository.findByEmail(adminEmail)
                .orElse(null); // Graceful fallback — admin may not exist yet
    }

    private Book findBookOrThrow(UUID bookId) {
        return bookRepository.findByIdOptional(bookId)
                .orElseThrow(() -> new ResourceNotFoundException("Book not found"));
    }

    private Book findBookOwnedByAuthor(UUID authorId, UUID bookId) {
        Book book = findBookOrThrow(bookId);
        if (!book.getAuthor().getId().equals(authorId)) {
            throw new ForbiddenException("You do not own this book");
        }
        if (book.getStatus() == BookStatus.DELETED) {
            throw new ResourceNotFoundException("Book not found");
        }
        return book;
    }

    private User findAuthorOrThrow(UUID authorId) {
        User user = userRepository.findByIdOptional(authorId)
                .orElseThrow(() -> new ResourceNotFoundException("Author not found"));
        if (user.getUserType() != UserType.AUTHOR) {
            throw new ForbiddenException("Only authors can manage books");
        }
        return user;
    }

    private String resolveAuthorName(User author) {
        return userProfileRepository.findByUserId(author.getId())
                .map(this::fullName)
                .filter(name -> !name.isBlank())
                .orElse(author.getEmail());
    }

    private String fullName(UserProfile profile) {
        String first = profile.getFirstName() == null ? "" : profile.getFirstName().trim();
        String last = profile.getLastName() == null ? "" : profile.getLastName().trim();
        return (first + " " + last).trim();
    }

    private Category findActiveCategoryOrThrow(UUID categoryId) {
        Category category = categoryRepository.findByIdOptional(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found"));
        if (!category.isActive()) {
            throw new ValidationException("Cannot assign book to inactive category");
        }
        return category;
    }

    private BookResponse toResponse(Book book) {
        return BookResponse.builder()
                .id(book.getId())
                .title(book.getTitle())
                .description(book.getDescription())
                .price(book.getPrice())
                .discount(book.getDiscount())
                .keywords(book.getKeywords())
                .publishedDate(book.getPublishedDate())
                .pages(book.getPages())
                .coverUrl(book.getCoverUrl())
                .previewUrl(book.getPreviewUrl())
                .bookUrl(fileKeyToUrl(book.getFileKey()))
                .versionNumber(book.getVersionNumber())
                .isPublished(book.isPublished())
                .status(book.getStatus().name())
                .rejectionReason(book.getRejectionReason())
                .categoryId(book.getCategory().getId())
                .categoryName(book.getCategory().getName())
                .authorId(book.getAuthor().getId())
                .authorName(resolveAuthorName(book.getAuthor()))
                .createdAt(book.getCreatedAt())
                .updatedAt(book.getUpdatedAt())
                .build();
    }

    /**
     * Extracts the storage key from a public file URL the FE sends us.
     * Accepts either a relative path ({@code /ebook/files/books/uuid.pdf}) or
     * an absolute URL pointing at the same backend. Returns {@code null} for
     * blank input or unrecognized shapes — callers should treat null as "no
     * book file attached", which is the correct state for a pending book.
     */
    private String urlToFileKey(String url) {
        if (url == null || url.isBlank()) return null;
        String trimmed = url.trim();
        String prefix = publicFileBaseUrl.endsWith("/") ? publicFileBaseUrl : publicFileBaseUrl + "/";
        int idx = trimmed.indexOf(prefix);
        if (idx >= 0) {
            return trimmed.substring(idx + prefix.length());
        }
        // Already a bare key? (books/abc.pdf)
        if (trimmed.startsWith("books/") || trimmed.startsWith("covers/")
                || trimmed.startsWith("previews/") || trimmed.startsWith("profiles/")) {
            return trimmed;
        }
        return null;
    }

    private String fileKeyToUrl(String key) {
        if (key == null || key.isBlank()) return null;
        String base = publicFileBaseUrl;
        if (base.startsWith("/")) {
            String host = backendBaseUrl.endsWith("/")
                    ? backendBaseUrl.substring(0, backendBaseUrl.length() - 1)
                    : backendBaseUrl;
            base = host + base;
        }
        String prefix = base.endsWith("/") ? base : base + "/";
        return prefix + key;
    }

    private BookApprovalLogResponse toLogResponse(BookApprovalLog log) {
        return BookApprovalLogResponse.builder()
                .id(log.getId())
                .bookId(log.getBook().getId())
                .bookTitle(log.getBook().getTitle())
                .senderId(log.getSender().getId())
                .senderEmail(log.getSender().getEmail())
                .receiverId(log.getReceiver().getId())
                .receiverEmail(log.getReceiver().getEmail())
                .action(log.getAction().name())
                .message(log.getMessage())
                .createdAt(log.getCreatedAt())
                .build();
    }
}
