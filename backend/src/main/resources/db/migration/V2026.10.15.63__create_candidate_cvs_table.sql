/*
  Flyway migration creating the candidate_cvs table.
  Mirrors the fields of com.guardwork.backend.candidate.cvs.CandidateCv entity.
*/
CREATE TABLE candidate_cvs (
    id UUID PRIMARY KEY,
    candidate_id UUID NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    file_size BIGINT NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    public_id VARCHAR(255) NOT NULL,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT now()
);

-- Index for quick lookup by candidate_id
CREATE INDEX idx_candidate_cvs_candidate_id ON candidate_cvs(candidate_id);
