package com.ebook.auth.repository;

import com.ebook.auth.entity.UserRole;
import com.ebook.auth.enums.RoleName;
import com.ebook.common.repository.BaseRepository;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class UserRoleRepository extends BaseRepository<UserRole, Long> {

    public List<UserRole> findByUserId(UUID userId) {
        return list("user.id", userId);
    }

    public List<RoleName> findRoleNamesByUserId(UUID userId) {
        return getEntityManager()
                .createQuery(
                        "SELECT ur.role.name FROM UserRole ur WHERE ur.user.id = :userId",
                        RoleName.class)
                .setParameter("userId", userId)
                .getResultList();
    }
}
