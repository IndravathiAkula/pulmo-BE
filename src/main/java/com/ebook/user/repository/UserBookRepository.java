package com.ebook.user.repository;

import com.ebook.common.repository.BaseRepository;
import com.ebook.user.entity.UserBook;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class UserBookRepository extends BaseRepository<UserBook, UUID> {

    public static final Set<String> SORTABLE_FIELDS =
            Set.of("accessGrantedAt", "lastReadAt", "progressPercentage");
    public static final String DEFAULT_SORT_FIELD = "accessGrantedAt";

    public Optional<UserBook> findByUserAndBook(UUID userId, UUID bookId) {
        return find("user.id = ?1 AND book.id = ?2", userId, bookId).firstResultOptional();
    }

    public boolean existsByUserAndBook(UUID userId, UUID bookId) {
        return count("user.id = ?1 AND book.id = ?2", userId, bookId) > 0;
    }
}
