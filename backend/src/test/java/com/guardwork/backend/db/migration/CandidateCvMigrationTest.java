package com.guardwork.backend.db.migration;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Approved PostgreSQL integrity boundary for candidate CV ownership, default and storage keys. */
@SpringBootTest
@ActiveProfiles("test")
class CandidateCvMigrationTest {

    @Autowired JdbcTemplate database;

    @Test
    void candidateCvMustReferenceAProfileAndIsRemovedWithThatProfile() {
        long user = user();
        UUID profile = profile(user);
        UUID cv = UUID.randomUUID();
        insertCv(cv, profile, publicId(), false);

        assertThat(count("SELECT count(*) FROM candidate_cvs WHERE id=?", cv)).isEqualTo(1);
        assertThatThrownBy(() -> insertCv(UUID.randomUUID(), UUID.randomUUID(), publicId(), false))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("fk_candidate_cvs_candidate_id");

        database.update("DELETE FROM candidate_profiles WHERE id=?", profile);
        assertThat(count("SELECT count(*) FROM candidate_cvs WHERE id=?", cv)).isZero();
    }

    @Test
    void candidateCanHaveOnlyOneDefaultCvButManyOtherCvs() {
        UUID profile = profile(user());
        insertCv(UUID.randomUUID(), profile, publicId(), true);

        assertThatThrownBy(() -> insertCv(UUID.randomUUID(), profile, publicId(), true))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_candidate_cvs_one_default");

        insertCv(UUID.randomUUID(), profile, publicId(), false);
        assertThat(count("SELECT count(*) FROM candidate_cvs WHERE candidate_id=? AND is_default", profile))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM candidate_cvs WHERE candidate_id=?", profile)).isEqualTo(2);
    }

    @Test
    void cloudinaryPublicIdIsGloballyUnique() {
        UUID firstProfile = profile(user());
        UUID secondProfile = profile(user());
        String sharedPublicId = publicId();
        insertCv(UUID.randomUUID(), firstProfile, sharedPublicId, false);

        assertThatThrownBy(() -> insertCv(UUID.randomUUID(), secondProfile, sharedPublicId, false))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_candidate_cvs_public_id");
    }

    long user() {
        String identity = "cv_" + UUID.randomUUID().toString().replace("-", "");
        return database.queryForObject(
                "INSERT INTO users(first_name,last_name,username,email,password) VALUES ('Ánh','Nguyễn',?,?,'fixture') RETURNING id",
                Long.class, identity, identity + "@example.vn");
    }

    UUID profile(long user) {
        return database.queryForObject(
                "INSERT INTO candidate_profiles(user_id,full_name) VALUES (?,'Nguyễn Văn An') RETURNING id",
                UUID.class, user);
    }

    void insertCv(UUID id, UUID candidateId, String publicId, boolean isDefault) {
        database.update("""
                INSERT INTO candidate_cvs(id,candidate_id,file_name,file_size,content_type,public_id,is_default)
                VALUES (?,?,'cv.pdf',1024,'application/pdf',?,?)
                """, id, candidateId, publicId, isDefault);
    }

    long count(String sql, Object... arguments) {
        return database.queryForObject(sql, Long.class, arguments);
    }

    String publicId() {
        return "candidates/" + UUID.randomUUID() + "/cvs/" + UUID.randomUUID();
    }
}
