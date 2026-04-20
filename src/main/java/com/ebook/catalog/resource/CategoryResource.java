package com.ebook.catalog.resource;

import com.ebook.catalog.dto.CategoryResponse;
import com.ebook.catalog.dto.CreateCategoryRequest;
import com.ebook.catalog.dto.UpdateCategoryRequest;
import com.ebook.catalog.repository.CategoryRepository;
import com.ebook.catalog.service.CategoryService;
import com.ebook.common.dto.ApiResponse;
import com.ebook.common.dto.PageRequest;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.UUID;

@Path("/categories")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CategoryResource {

    private final CategoryService categoryService;

    public CategoryResource(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    // ─────────────────────────── PUBLIC ───────────────────────────

    @GET
    @PermitAll
    public Response getActiveCategories(@QueryParam("page") Integer page,
                                        @QueryParam("size") Integer size,
                                        @QueryParam("sort") String sort) {
        PageRequest req = PageRequest.parse(page, size, sort,
                CategoryRepository.SORTABLE_FIELDS, CategoryRepository.DEFAULT_SORT_FIELD);
        if (req == null) {
            List<CategoryResponse> categories = categoryService.getActiveCategories();
            return Response.ok(ApiResponse.success(categories, "Categories retrieved")).build();
        }
        return Response.ok(ApiResponse.success(
                categoryService.getActiveCategories(req), "Categories retrieved")).build();
    }

    @GET
    @Path("/{id}")
    @PermitAll
    public Response getById(@PathParam("id") UUID id) {
        CategoryResponse category = categoryService.getById(id);
        return Response.ok(ApiResponse.success(category, "Category retrieved")).build();
    }

    // ─────────────────────────── ADMIN ───────────────────────────

    @GET
    @Path("/admin/all")
    @RolesAllowed({"ADMIN"})
    public Response getAllForAdmin(@QueryParam("page") Integer page,
                                   @QueryParam("size") Integer size,
                                   @QueryParam("sort") String sort) {
        PageRequest req = PageRequest.parse(page, size, sort,
                CategoryRepository.SORTABLE_FIELDS, CategoryRepository.DEFAULT_SORT_FIELD);
        if (req == null) {
            List<CategoryResponse> categories = categoryService.getAllForAdmin();
            return Response.ok(ApiResponse.success(categories, "All categories retrieved")).build();
        }
        return Response.ok(ApiResponse.success(
                categoryService.getAllForAdmin(req), "All categories retrieved")).build();
    }

    @POST
    @RolesAllowed({"ADMIN"})
    public Response create(@Valid CreateCategoryRequest request) {
        CategoryResponse category = categoryService.create(request);
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.success(category, "Category created"))
                .build();
    }

    @PUT
    @Path("/{id}")
    @RolesAllowed({"ADMIN"})
    public Response update(@PathParam("id") UUID id, @Valid UpdateCategoryRequest request) {
        CategoryResponse category = categoryService.update(id, request);
        return Response.ok(ApiResponse.success(category, "Category updated")).build();
    }

    @DELETE
    @Path("/{id}")
    @RolesAllowed({"ADMIN"})
    public Response delete(@PathParam("id") UUID id) {
        categoryService.delete(id);
        return Response.ok(ApiResponse.success(null, "Category deleted")).build();
    }

    @PATCH
    @Path("/{id}/toggle")
    @RolesAllowed({"ADMIN"})
    public Response toggleActive(@PathParam("id") UUID id) {
        CategoryResponse category = categoryService.toggleActive(id);
        String message = category.isActive() ? "Category activated" : "Category deactivated";
        return Response.ok(ApiResponse.success(category, message)).build();
    }
}
