package com.example.ragbot.repository;

import com.example.ragbot.domain.AppUser;
import com.example.ragbot.domain.DocumentStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class UserRepository {

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<AppUser> MAPPER = (rs, n) -> new AppUser(
            UUID.fromString(rs.getString("id")),
            rs.getString("username"),
            rs.getString("email"),
            rs.getString("password_hash"),
            rs.getString("role"),
            rs.getBoolean("must_change_password"),
            rs.getBoolean("enabled"),
            rs.getObject("created_at", java.time.OffsetDateTime.class)
    );

    public Optional<AppUser> findById(UUID id) {
        return jdbc.query("SELECT * FROM users WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public Optional<AppUser> findByUsername(String username) {
        return jdbc.query("SELECT * FROM users WHERE username = ?", MAPPER, username).stream().findFirst();
    }

    public Optional<AppUser> findByEmail(String email) {
        return jdbc.query("SELECT * FROM users WHERE email = ?", MAPPER, email).stream().findFirst();
    }

    public boolean existsByUsername(String username) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT count(*) > 0 FROM users WHERE username = ?", Boolean.class, username));
    }

    public boolean existsByEmail(String email) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT count(*) > 0 FROM users WHERE email = ?", Boolean.class, email));
    }

    public UUID insert(String username, String email, String passwordHash, String role, boolean mustChangePassword) {
        return jdbc.queryForObject("""
                INSERT INTO users (username, email, password_hash, role, must_change_password)
                VALUES (?, ?, ?, ?, ?) RETURNING id
                """, UUID.class, username, email, passwordHash, role, mustChangePassword);
    }

    public void updatePassword(UUID id, String passwordHash) {
        jdbc.update("UPDATE users SET password_hash = ?, must_change_password = FALSE, updated_at = now() WHERE id = ?",
                passwordHash, id);
    }

    public void setMustChangePassword(UUID id, boolean value) {
        jdbc.update("UPDATE users SET must_change_password = ?, updated_at = now() WHERE id = ?", value, id);
    }

    public void setEnabled(UUID id, boolean value) {
        jdbc.update("UPDATE users SET enabled = ?, updated_at = now() WHERE id = ?", value, id);
    }
}
