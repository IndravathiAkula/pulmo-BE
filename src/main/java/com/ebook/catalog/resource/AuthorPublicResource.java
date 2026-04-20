package com.ebook.catalog.resource;

import com.ebook.admin.dto.AuthorResponse;
import com.ebook.admin.service.AdminAuthorService;
import com.ebook.common.dto.ApiResponse;
import com.ebook.common.dto.PageRequest;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Set;

@Path("/authors")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@PermitAll
public class AuthorPublicResource {

    // Spec §3.5 allow-list for /authors sort — distinct from UserProfileRepository's default set
    // because firstName/lastName are valid here but not every profile-level caller.
    private static final Set<String> SORTABLE_FIELDS = Set.of("createdAt", "firstName", "lastName");
    private static final String DEFAULT_SORT_FIELD = "createdAt";

    private final AdminAuthorService authorService;

    public AuthorPublicResource(AdminAuthorService authorService) {
        this.authorService = authorService;
    }

    @GET
    public Response getActiveAuthors(@QueryParam("page") Integer page,
                                     @QueryParam("size") Integer size,
                                     @QueryParam("sort") String sort) {
        PageRequest req = PageRequest.parse(page, size, sort, SORTABLE_FIELDS, DEFAULT_SORT_FIELD);
        if (req == null) {
            List<AuthorResponse> authors = authorService.getActiveAuthors();
            return Response.ok(ApiResponse.success(authors, "Authors retrieved")).build();
        }
        return Response.ok(ApiResponse.success(authorService.getActiveAuthors(req), "Authors retrieved")).build();
    }
}
