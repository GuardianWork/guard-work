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

import com.guardwork.backend.auth.model.RefreshToken;

@Repository
public class JdbcRefreshTokenRepository implements RefreshTokenRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public JdbcRefreshTokenRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<RefreshToken> TOKEN_MAPPER = (rs, rowNum) -> {
        RefreshToken token = new RefreshToken();
        token.setId(rs.getLong("id"));
        token.setUserId(rs.getLong("user_id"));
        token.setTokenHash(rs.getString("token_hash"));
        token.setFamilyId(rs.getString("family_id"));
        token.setRevoked(rs.getBoolean("is_revoked"));
        token.setExpiresAt(rs.getTimestamp("expires_at").toInstant());
        token.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        Timestamp revokedAt = rs.getTimestamp("revoked_at");
        token.setRevokedAt(revokedAt != null ? revokedAt.toInstant() : null);
        token.setUserAgent(rs.getString("user_agent"));
        token.setIpAddress(rs.getString("ip_address"));
        return token;
    };

    @Override
    public RefreshToken save(RefreshToken token) {
        String sql = """
                INSERT INTO refresh_tokens (user_id, token_hash, family_id, is_revoked, expires_at, created_at, revoked_at, user_agent, ip_address)
                VALUES (:userId, :tokenHash, :familyId, :isRevoked, :expiresAt, :createdAt, :revokedAt, :userAgent, :ipAddress)
                """;

        Instant now = token.getCreatedAt() != null ? token.getCreatedAt() : Instant.now();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", token.getUserId())
                .addValue("tokenHash", token.getTokenHash())
                .addValue("familyId", token.getFamilyId())
                .addValue("isRevoked", token.isRevoked())
                .addValue("expiresAt", Timestamp.from(token.getExpiresAt()))
                .addValue("createdAt", Timestamp.from(now))
                .addValue("revokedAt", token.getRevokedAt() != null ? Timestamp.from(token.getRevokedAt()) : null)
                .addValue("userAgent", token.getUserAgent())
                .addValue("ipAddress", token.getIpAddress());

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
    public Optional<RefreshToken> findByTokenHash(String tokenHash) {
        String sql = "SELECT * FROM refresh_tokens WHERE token_hash = :tokenHash";
        return jdbcTemplate.query(sql, new MapSqlParameterSource("tokenHash", tokenHash), TOKEN_MAPPER).stream().findFirst();
    }

    @Override
    public void revokeToken(Long id, Instant revokedAt) {
        String sql = "UPDATE refresh_tokens SET is_revoked = true, revoked_at = :revokedAt WHERE id = :id";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("revokedAt", Timestamp.from(revokedAt != null ? revokedAt : Instant.now()));
        jdbcTemplate.update(sql, params);
    }

    @Override
    public void revokeFamily(String familyId, Instant revokedAt) {
        String sql = "UPDATE refresh_tokens SET is_revoked = true, revoked_at = :revokedAt WHERE family_id = :familyId";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("familyId", familyId)
                .addValue("revokedAt", Timestamp.from(revokedAt != null ? revokedAt : Instant.now()));
        jdbcTemplate.update(sql, params);
    }

    @Override
    public void revokeAllForUser(Long userId, Instant revokedAt) {
        String sql = "UPDATE refresh_tokens SET is_revoked = true, revoked_at = :revokedAt WHERE user_id = :userId";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("revokedAt", Timestamp.from(revokedAt != null ? revokedAt : Instant.now()));
        jdbcTemplate.update(sql, params);
    }
}
