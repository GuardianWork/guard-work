ALTER TABLE companies
    ADD COLUMN email VARCHAR(255);

CREATE INDEX idx_companies_email ON companies (email);
