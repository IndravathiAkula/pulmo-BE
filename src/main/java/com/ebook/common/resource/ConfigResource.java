package com.ebook.common.resource;

import com.ebook.common.dto.ApiResponse;
import com.ebook.common.dto.ConfigParamResponse;
import com.ebook.common.entity.ConfigParam;
import com.ebook.common.service.ConfigService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

@Path("/config")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ConfigResource {

    private final ConfigService configService;

    public ConfigResource(ConfigService configService) {
        this.configService = configService;
    }

    @GET
    @Path("/params")
    @RolesAllowed({"ADMIN"})
    public Response getAllParams() {
        List<ConfigParamResponse> params = configService.getAll().stream()
                .map(this::toResponse)
                .toList();
        return Response.ok(ApiResponse.success(params, "Config params retrieved")).build();
    }

    @GET
    @Path("/params/keys")
    @RolesAllowed({"USER", "ADMIN"})
    public Response getByKeys(@QueryParam("keys") String keys) {
        if (keys == null || keys.isBlank()) {
            return Response.ok(ApiResponse.success(List.of(), "No keys provided")).build();
        }
        List<String> keyList = List.of(keys.split(","));
        List<ConfigParamResponse> params = configService.getByKeys(keyList).stream()
                .map(this::toResponse)
                .toList();
        return Response.ok(ApiResponse.success(params, "Config params retrieved")).build();
    }

    @POST
    @Path("/params/refresh")
    @RolesAllowed({"ADMIN"})
    public Response refreshCache() {
        configService.refreshCache();
        return Response.ok(ApiResponse.success(null, "Config cache refreshed")).build();
    }

    @PUT
    @Path("/params/{key}")
    @RolesAllowed({"ADMIN"})
    public Response updateParam(@PathParam("key") String key, java.util.Map<String, String> body) {
        String value = body != null ? body.get("value") : null;
        if (value == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("Field 'value' is required"))
                    .build();
        }
        ConfigParam updated = configService.updateValue(key, value);
        return Response.ok(ApiResponse.success(toResponse(updated), "Config param updated")).build();
    }

    private ConfigParamResponse toResponse(ConfigParam param) {
        return ConfigParamResponse.builder()
                .name(param.getName())
                .key(param.getKey())
                .value(param.getValue() != null ? param.getValue() : param.getDefaultValue())
                .defaultValue(param.getDefaultValue())
                .possibleValues(param.getPossibleValues())
                .type(param.getType())
                .build();
    }
}
