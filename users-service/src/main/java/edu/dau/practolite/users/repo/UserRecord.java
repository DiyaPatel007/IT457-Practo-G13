package edu.dau.practolite.users.repo;

import edu.dau.practolite.common.security.Role;

/** One item of the Users table. */
public record UserRecord(String userId, String email, String name, String phone, Role role,
                         String createdAt, String updatedAt) {
}
