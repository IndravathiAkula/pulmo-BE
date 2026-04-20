package com.ebook.catalog.service;

import com.ebook.catalog.dto.CategoryResponse;
import com.ebook.catalog.dto.CreateCategoryRequest;
import com.ebook.catalog.dto.UpdateCategoryRequest;
import com.ebook.catalog.entity.Category;
import com.ebook.catalog.repository.CategoryRepository;
import com.ebook.common.dto.PageRequest;
import com.ebook.common.dto.PagedResponse;
import com.ebook.common.exception.ConflictException;
import com.ebook.common.exception.ResourceNotFoundException;
import com.ebook.common.exception.ValidationException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class CategoryService {

    private static final Logger LOG = Logger.getLogger(CategoryService.class);

    private final CategoryRepository categoryRepository;

    public CategoryService(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    // ─────────────────────────── CREATE ───────────────────────────

    @Transactional
    public CategoryResponse create(CreateCategoryRequest request) {
        String name = request.getName().trim();
        String slug = toSlug(name);

        if (categoryRepository.findByName(name).isPresent()) {
            throw new ConflictException("Category with name '" + name + "' already exists");
        }
        if (categoryRepository.findBySlug(slug).isPresent()) {
            throw new ConflictException("Category with slug '" + slug + "' already exists");
        }

        Category category = new Category();
        category.setName(name);
        category.setSlug(slug);
        category.setDescription(request.getDescription());
        category.setActive(true);
        categoryRepository.save(category);

        LOG.infof("Category created: %s (slug: %s)", name, slug);
        return toResponse(category);
    }

    // ─────────────────────────── UPDATE ───────────────────────────

    @Transactional
    public CategoryResponse update(UUID id, UpdateCategoryRequest request) {
        Category category = findByIdOrThrow(id);

        String name = request.getName().trim();
        String slug = toSlug(name);

        // Check uniqueness only if name changed
        if (!category.getName().equals(name)) {
            categoryRepository.findByName(name).ifPresent(existing -> {
                if (!existing.getId().equals(id)) {
                    throw new ConflictException("Category with name '" + name + "' already exists");
                }
            });
        }

        category.setName(name);
        category.setSlug(slug);
        category.setDescription(request.getDescription());
        categoryRepository.update(category);

        LOG.infof("Category updated: %s", id);
        return toResponse(category);
    }

    // ─────────────────────────── DELETE ───────────────────────────

    @Transactional
    public void delete(UUID id) {
        Category category = findByIdOrThrow(id);

        // Check if category has books — prevent deletion if so
        if (!category.getBooks().isEmpty()) {
            throw new ValidationException("Cannot delete category '" + category.getName()
                    + "' — it has " + category.getBooks().size() + " book(s). Deactivate instead.");
        }

        categoryRepository.delete(category);
        LOG.infof("Category deleted: %s (%s)", category.getName(), id);
    }

    // ─────────────────────────── TOGGLE ───────────────────────────

    @Transactional
    public CategoryResponse toggleActive(UUID id) {
        Category category = findByIdOrThrow(id);
        category.setActive(!category.isActive());
        categoryRepository.update(category);

        LOG.infof("Category %s: %s (id: %s)",
                category.isActive() ? "activated" : "deactivated", category.getName(), id);
        return toResponse(category);
    }

    // ─────────────────────────── READ ───────────────────────────

    @Transactional
    public List<CategoryResponse> getAllForAdmin() {
        return categoryRepository.findAllOrderByName().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public List<CategoryResponse> getActiveCategories() {
        return categoryRepository.findAllActive().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public CategoryResponse getById(UUID id) {
        return toResponse(findByIdOrThrow(id));
    }

    @Transactional
    public PagedResponse<CategoryResponse> getActiveCategories(PageRequest req) {
        return PagedResponse.of(
                categoryRepository.findActivePage(req),
                categoryRepository.countActive(),
                req, this::toResponse);
    }

    @Transactional
    public PagedResponse<CategoryResponse> getAllForAdmin(PageRequest req) {
        return PagedResponse.of(
                categoryRepository.findAllPage(req),
                categoryRepository.countAll(),
                req, this::toResponse);
    }

    // ─────────────────────────── HELPERS ───────────────────────────

    private Category findByIdOrThrow(UUID id) {
        return categoryRepository.findByIdOptional(id)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found"));
    }

    private String toSlug(String name) {
        return name.toLowerCase()
                .replaceAll("[^a-z0-9\\s-]", "")
                .replaceAll("\\s+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
    }

    private CategoryResponse toResponse(Category category) {
        return CategoryResponse.builder()
                .id(category.getId())
                .name(category.getName())
                .slug(category.getSlug())
                .description(category.getDescription())
                .isActive(category.isActive())
                .createdAt(category.getCreatedAt())
                .updatedAt(category.getUpdatedAt())
                .build();
    }
}
