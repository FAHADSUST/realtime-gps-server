package com.rls.gps.id.user.dto;

import java.util.List;

import org.springframework.data.domain.Page;

/**
 * A page of users in a shape this service owns.
 *
 * <p>Returning Spring's {@code Page} directly would leak its internal JSON structure into the public
 * contract, and that structure has changed between Spring versions.
 */
public record PagedUsersResponse(List<UserResponse> users,
                                 int page,
                                 int size,
                                 long totalElements,
                                 int totalPages) {

    public static PagedUsersResponse from(Page<com.rls.gps.id.user.User> page) {
        return new PagedUsersResponse(
                page.getContent().stream().map(UserResponse::from).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
