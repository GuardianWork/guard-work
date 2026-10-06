---
status: approved
---

# Migrate persistence layer to Spring Data JPA

GuardWork replaces direct SQL `JdbcTemplate` repository implementations with Spring Data JPA repository interfaces and Jakarta Persistence annotations on model entities. Hibernate DDL generation is disabled (`spring.jpa.hibernate.ddl-auto=none`) to preserve Flyway migrations as the single source of truth for the database schema.

This migration reduces boilerplate data-access code while maintaining existing repository contracts, pagination semantics, and optimistic locking mechanisms across services.
