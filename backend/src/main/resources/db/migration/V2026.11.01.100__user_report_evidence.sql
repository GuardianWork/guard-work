CREATE TABLE user_report_evidence (
    id UUID PRIMARY KEY,
    report_id BIGINT NOT NULL REFERENCES user_reports(id),
    filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(30) NOT NULL CHECK (content_type IN ('application/pdf', 'image/png', 'image/jpeg')),
    size_bytes BIGINT NOT NULL CHECK (size_bytes BETWEEN 1 AND 5242880),
    content_hash VARCHAR(64) NOT NULL,
    content BYTEA,
    UNIQUE (report_id, content_hash),
    CHECK (content IS NULL OR octet_length(content) = size_bytes)
);
CREATE INDEX idx_user_report_evidence_report ON user_report_evidence(report_id);
