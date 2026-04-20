package com.ebook.auth.repository;

import com.ebook.auth.entity.Role;
import com.ebook.auth.enums.RoleName;
import com.ebook.common.repository.BaseRepository;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;


@ApplicationScoped
public class RoleRepository extends BaseRepository<Role, Long> {


    public Optional<Role> findByName(RoleName name) {
        return find("name", name).firstResultOptional();
    }
}
