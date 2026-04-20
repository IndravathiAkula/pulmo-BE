package com.ebook.catalog.repository;

import com.ebook.catalog.entity.Category;
import com.ebook.common.dto.PageRequest;
import com.ebook.common.repository.BaseRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class CategoryRepository extends BaseRepository<Category, UUID> {

    public static final Set<String> SORTABLE_FIELDS = Set.of("name", "createdAt");
    public static final String DEFAULT_SORT_FIELD = "name";

    public Optional<Category> findBySlug(String slug) {
        return find("slug", slug).firstResultOptional();
    }

    public Optional<Category> findByName(String name) {
        return find("name", name).firstResultOptional();
    }

    public List<Category> findAllActive() {
        return list("isActive", true);
    }

    public List<Category> findAllOrderByName() {
        return list("ORDER BY name");
    }

    public List<Category> findActivePage(PageRequest req) {
        return getEntityManager()
                .createQuery("SELECT c FROM Category c WHERE c.isActive = true " + req.orderByClause("c"),
                        Category.class)
                .setFirstResult(req.getPage() * req.getSize())
                .setMaxResults(req.getSize())
                .getResultList();
    }

    public long countActive() {
        return getEntityManager()
                .createQuery("SELECT COUNT(c) FROM Category c WHERE c.isActive = true", Long.class)
                .getSingleResult();
    }

    public List<Category> findAllPage(PageRequest req) {
        return getEntityManager()
                .createQuery("SELECT c FROM Category c " + req.orderByClause("c"), Category.class)
                .setFirstResult(req.getPage() * req.getSize())
                .setMaxResults(req.getSize())
                .getResultList();
    }

    public long countAll() {
        return getEntityManager()
                .createQuery("SELECT COUNT(c) FROM Category c", Long.class)
                .getSingleResult();
    }
}
