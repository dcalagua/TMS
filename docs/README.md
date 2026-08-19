# TMS by EBIM - documentation

| Directory | Contents |
|---|---|
| `architecture/` | Architecture of record, ownership matrix and ADRs. **Read before changing schema, security or module boundaries.** |
| `database/` | Schema notes, tenancy model and migration conventions |
| `security/` | Security baseline, authentication and authorization model |
| `overnight/` | Step-by-step reports produced by the unattended build sequence |

## Start here

1. [`architecture/TMS_ARCHITECTURE_V1.md`](architecture/TMS_ARCHITECTURE_V1.md) - the V1 architecture of record.
2. [`architecture/OWNERSHIP_MATRIX.md`](architecture/OWNERSHIP_MATRIX.md) - who owns which concern.
3. [`architecture/ADR-001-layered-architecture.md`](architecture/ADR-001-layered-architecture.md) - React -> Spring Boot -> PostgreSQL.
4. [`architecture/ADR-002-migration-ownership-flyway.md`](architecture/ADR-002-migration-ownership-flyway.md) - Flyway owns application DDL.
5. [`architecture/ADR-003-multitenancy-company-scope.md`](architecture/ADR-003-multitenancy-company-scope.md) - Organization/Company tenancy.
6. [`architecture/ADR-004-application-schema-and-database-exposure.md`](architecture/ADR-004-application-schema-and-database-exposure.md) - the `tms` schema and the closed database exposure posture.

If an implementation must deviate from these documents, add a new ADR rather than
silently diverging.

## Database and security

7. [`database/DATA_MODEL.md`](database/DATA_MODEL.md) - schema of record and the tenancy design.
8. [`database/MIGRATION_STRATEGY.md`](database/MIGRATION_STRATEGY.md) - how migrations are written, tested and replayed.
9. [`security/RLS_STRATEGY.md`](security/RLS_STRATEGY.md) - database-level exposure decision and RLS posture.
