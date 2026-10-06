package com.guardwork.backend.auth.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.guardwork.backend.auth.model.PasswordResetToken;

@Repository
public class JdbcPasswordResetTokenRepository implements PasswordResetTokenRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public JdbcPasswordResetTokenRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<PasswordResetToken> RESET_MAPPER = (rs, rowNum) -> {
        PasswordResetToken token = new PasswordResetToken();
        token.setId(rs.getLong("id"));
        token.setUserId(rs.getLong("user_id"));
        token.setTokenHash(rs.getString("token_hash"));
        token.setConsumed(rs.getBoolean("is_consumed"));
        token.setExpiresAt(rs.getTimestamp("expires_at").toInstant());
        token.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        return token;
    };

    @Override
    public PasswordResetToken save(PasswordResetToken token) {
        String sql = """
                INSERT INTO password_reset_tokens (user_id, token_hash, is_consumed, expires_at, created_at)
                VALUES (:userId, :tokenHash, :isConsumed, :expiresAt, :createdAt)
                """;

        Instant now = token.getCreatedAt() != null ? token.getCreatedAt() : Instant.now();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", token.getUserId())
                .addValue("tokenHash", token.getTokenHash())
                .addValue("isConsumed", token.isConsumed())
                .addValue("expiresAt", Timestamp.from(token.getExpiresAt()))
                .addValue("createdAt", Timestamp.from(now));

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(sql, params, keyHolder, new String[]{"id"});

        Number key = keyHolder.getKey();
        if (key != null) {
            token.setId(key.longValue());
        }
        token.setCreatedAt(now);
        return token;
    }

    @Override
    public Optional<PasswordResetToken> findByTokenHash(String tokenHash) {
        String sql = "SELECT * FROM password_reset_tokens WHERE token_hash = :tokenHash";
        return jdbcTemplate.query(sql, new MapSqlParameterSource("tokenHash", tokenHash), RESET_MAPPER).stream().findFirst();
    }

    @Override
    public void markConsumed(Long id) {
        String sql = "UPDATE password_reset_tokens SET is_consumed = true WHERE id = :id";
        jdbcTemplate.update(sql, new MapSqlParameterSource("id", id));
    }
}
