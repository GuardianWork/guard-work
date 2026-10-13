CREATE TABLE candidate_profiles (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id            BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    full_name          VARCHAR(150) NOT NULL,
    avatar_url         VARCHAR(512),
    phone_number       VARCHAR(20),
    skills             JSONB NOT NULL DEFAULT '[]'::jsonb,
    experience_summary TEXT,
    education_summary  TEXT,
    is_public          BOOLEAN NOT NULL DEFAULT true,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_candidate_profiles_user_id UNIQUE (user_id)
);

CREATE INDEX idx_candidate_profiles_user_id ON candidate_profiles (user_id);
CREATE INDEX idx_candidate_profiles_is_public ON candidate_profiles (is_public);
