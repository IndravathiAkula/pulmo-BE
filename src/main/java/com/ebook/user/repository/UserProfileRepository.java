package com.ebook.user.repository;

import com.ebook.auth.enums.UserType;
import com.ebook.common.dto.PageRequest;
import com.ebook.common.repository.BaseRepository;
import com.ebook.user.entity.UserProfile;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class UserProfileRepository extends BaseRepository<UserProfile, UUID> {

    public static final Set<String> SORTABLE_FIELDS =
            Set.of("firstName", "lastName", "createdAt", "updatedAt");
    public static final String DEFAULT_SORT_FIELD = "createdAt";

    public Optional<UserProfile> findByUserId(UUID userId) {
        return find("user.id", userId).firstResultOptional();
    }

    public List<UserProfile> findAllByUserType(UserType userType) {
        return getEntityManager()
                .createQuery("SELECT p FROM UserProfile p JOIN FETCH p.user u WHERE u.userType = :userType", UserProfile.class)
                .setParameter("userType", userType)
                .getResultList();
    }

    public List<UserProfile> findActiveAuthorProfiles() {
        return getEntityManager()
                .createQuery("SELECT p FROM UserProfile p JOIN FETCH p.user u " +
                        "WHERE u.userType = :userType AND u.emailVerified = true AND p.isActive = true",
                        UserProfile.class)
                .setParameter("userType", UserType.AUTHOR)
                .getResultList();
    }

    public List<UserProfile> findAllByUserTypePage(UserType userType, PageRequest req) {
        String jpql = "SELECT p FROM UserProfile p JOIN FETCH p.user u " +
                "WHERE u.userType = :userType " + req.orderByClause("p");
        return getEntityManager()
                .createQuery(jpql, UserProfile.class)
                .setParameter("userType", userType)
                .setFirstResult(req.getPage() * req.getSize())
                .setMaxResults(req.getSize())
                .getResultList();
    }

    public long countByUserType(UserType userType) {
        return getEntityManager()
                .createQuery("SELECT COUNT(p) FROM UserProfile p WHERE p.user.userType = :userType", Long.class)
                .setParameter("userType", userType)
                .getSingleResult();
    }

    public List<UserProfile> findActiveAuthorProfilesPage(PageRequest req) {
        String jpql = "SELECT p FROM UserProfile p JOIN FETCH p.user u " +
                "WHERE u.userType = :userType AND u.emailVerified = true AND p.isActive = true " +
                req.orderByClause("p");
        return getEntityManager()
                .createQuery(jpql, UserProfile.class)
                .setParameter("userType", UserType.AUTHOR)
                .setFirstResult(req.getPage() * req.getSize())
                .setMaxResults(req.getSize())
                .getResultList();
    }

    public long countActiveAuthors() {
        return getEntityManager()
                .createQuery("SELECT COUNT(p) FROM UserProfile p " +
                        "WHERE p.user.userType = :userType AND p.user.emailVerified = true AND p.isActive = true",
                        Long.class)
                .setParameter("userType", UserType.AUTHOR)
                .getSingleResult();
    }
}
