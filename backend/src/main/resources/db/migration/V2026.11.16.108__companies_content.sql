/* GW-123: recruiter-authored public company profile ("content").
 *
 * Stores the company's self-declared, candidate-facing profile as one JSONB document so
 * the dossier can publish it without inventing facts (GW-CAN-COMPANY-08 keeps posting-derived
 * facts and verified facts separate from this payload). Empty object means unset; existing
 * rows get the default and their dossier response omits the profile section. */

ALTER TABLE companies
    ADD COLUMN IF NOT EXISTS content JSONB NOT NULL DEFAULT '{}'::jsonb;

COMMENT ON COLUMN companies.content IS
    'Recruiter-authored public company profile for candidate-facing display (GW-123). JSON object conforming to "schemaVersion": 1 with optional keys: industry (string, <=100), introduction (string, <=2000), workAddress (string, <=500), workingHours (string matching "(Thứ x|Chủ nhật) - (Thứ y|Chủ nhật)" with optional ", HH:MM - HH:MM" suffix; x,y in 2..7; start day must not be after end day), companyScale ({"employeeRange": one of 1-10|11-50|51-200|201-500|501-1000|1000+}), expertise (array of strings, <=10 items, each <=50, unique case-insensitively). Empty object means unset. Never holds GuardianWork-verified or posting-derived facts; those come from posting summaries. Written only by OWNER/ADMIN through PUT /api/companies/me/profile.';
