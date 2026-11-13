# FF-02 migrations and existing-database adoption

## Safety and migration inventory

Do not start the application against the existing fixflow database until its
identity schema has been compared and its explicit baseline approved. FF-02
implementation/testing does not baseline or migrate that local database.

Flyway uses public, validates checksums on migrate, prohibits clean, prohibits
automatic baselining, does not create schemas, and does not run out of order.
Hibernate DDL is disabled and Spring SQL initialization is disabled. Keep these
protections enabled. Never use ddl-auto=update or enable baseline-on-migrate to
work around a failed startup. A pre-existing schema without history should fail
rather than silently be adopted. Empty schemas run V1-V7 normally.

| Version | Purpose |
| --- | --- |
| V1 | Existing roles/users definitions, without seeds |
| V2 | Validate and seed fixed role mappings safely |
| V3 | Buildings, rooms, categories |
| V4 | ELECTRICAL, PLUMBING, HVAC, FURNITURE, NETWORK, CLEANING, OTHER |
| V5 | Requests, assignments, status history |
| V6 | Comments, attachment metadata, notifications |
| V7 | Email verification challenges |

Spring Boot manages Flyway versions through the existing parent. The Boot 4
Flyway starter provides auto-configuration; the PostgreSQL module supplies
database support. No Java/Spring Boot/dependency version upgrades are made.

V1 reproduces the supplied identity definitions, using PostgreSQL's conventional
names roles_pkey, roles_name_key, users_pkey, users_email_key,
users_student_staff_id_key, roles_id_seq and users_id_seq, and the supplied
fk_users_role name. Exact local names/sequence options must be verified before
baseline; these names were not independently inspected during implementation.
Do not claim equivalence solely because the columns match. Existing roles and
users are not altered by V2-V7.

## Existing-database procedure (manual; not executed)

1. Stop concurrent writers and application instances during adoption. Confirm
   database identity, schema/search path, server version, owner and privileges.
2. Back up the existing database and verify restoration into an isolated copy.
   Keep backups private; never commit data or credentials.
3. Capture roles/users columns (including order, exact types, collations,
   defaults, nullability), identity generation/sequence definitions and
   ownership, constraints (names, actions, validation, deferrability), indexes
   (names, definitions, predicates), triggers and dependent objects. Verify no
   Flyway history exists, role IDs 1=REPORTER, 2=TECHNICIAN, 3=ADMIN, user data,
   and sequence last_value/is_called using SELECT, never nextval.
4. Apply V1 only to an empty disposable database. Compare normalized catalog
   results and reviewed schema-only dumps with the existing identity schema.
   Ignore database/OID differences, not real semantic or naming differences.
   PostgreSQL 18 exposes NOT NULL metadata differently from 17; normalize that
   representation while preserving equivalent nullability. Check identity
   sequence type/start/increment/min/max/cache/cycle/ownership separately from
   live sequence state. Resolve discrepancies before proceeding. Do not alter
   the local schema just to make it match the proposed V1.
5. Rehearse explicit baseline version 1 and V2-V7 on the restored copy. Compare
   data, role IDs, identity structures and sequence states before/after.
6. Only after equivalence, backup and rehearsal succeed, explicitly baseline
   the intended existing database at version 1; inspect history before migrate.
7. Run migrations, validate, then compare schema with a fresh fully migrated
   database and verify unchanged existing data/IDs/sequences. Restart the app
   only after this passes. Never repair checksums to conceal a discrepancy.

For an explicitly approved adoption, use a Flyway CLI matching the version
managed by backend/pom.xml's Spring Boot parent. From backend/, configure its
FLYWAY_URL and FLYWAY_USER privately for the intended target, and supply
FLYWAY_PASSWORD privately through the environment (never literal CLI arguments,
tracked config or chat). FLYWAY_* variables are CLI settings; the application
continues to use DB_PASSWORD / Spring datasource bindings. With the target
checked and credentials already supplied, the reviewable CLI sequence is:

```powershell
flyway -locations=filesystem:src/main/resources/db/migration -schemas=public -defaultSchema=public -createSchemas=false -cleanDisabled=true -baselineOnMigrate=false info
flyway -locations=filesystem:src/main/resources/db/migration -schemas=public -defaultSchema=public -createSchemas=false -cleanDisabled=true -baselineOnMigrate=false -baselineVersion=1 baseline
flyway -locations=filesystem:src/main/resources/db/migration -schemas=public -defaultSchema=public -createSchemas=false -cleanDisabled=true -baselineOnMigrate=false migrate
flyway -locations=filesystem:src/main/resources/db/migration -schemas=public -defaultSchema=public -createSchemas=false -cleanDisabled=true -baselineOnMigrate=false validate
```

Baseline records version 1 without executing V1 or verifying its structure.
Do not baseline at version 7: that would skip creation of the missing tables.
Do not baseline a fresh database. Do not execute these commands before the
manual preconditions above. Flyway CLI is not required for automated tests.

V2 validates role ID/name mappings before insertion and verifies them afterward.
A fresh identity supplies IDs 1-3 naturally; matching existing roles result in
no inserts or sequence operations. Incompatible mappings or unexpected sequence
allocation fail instead of overwriting IDs or resetting sequences. A failed
sequence allocation may consume sequence values (PostgreSQL sequences are not
transactional); inspect and resolve explicitly rather than retrying blindly.
Existing sequence state is never reset to MAX(id).

## Recovery and deployment

Applied migrations are immutable; subsequent changes use new versions. Test
failed deployment recovery on the restored copy first. PostgreSQL transactional
DDL rolls back a failed migration's table changes, but not sequence advancement.
Review Flyway info and database state before retrying. For destructive or
unrecoverable failures, restore the verified backup under an approved recovery
procedure rather than dropping the existing database or issuing Flyway clean.
No automatic down migrations or cleanup jobs are provided.

## Automated verification

Java 21 and a running Docker engine are prerequisites. Tests create random
ff02_test_* databases only inside a Testcontainers PostgreSQL container. They
never read local database credentials or use the application's JDBC URL.
The existing context test also uses its own disposable database and verifies
Boot Flyway auto-configuration with the safe application settings.

From backend/:

```powershell
.\mvnw.cmd -v
.\mvnw.cmd -B clean verify
.\mvnw.cmd -B '-Dff02.postgres.image=postgres:18-alpine' clean verify
```

The default is PostgreSQL 17; the second verification targets 18. No tests are
silently skipped if Docker is unavailable. Testcontainers/Ryuk removes containers
and their databases when the JVM exits. No local fixflow data is touched.
The existing CI has Docker on its Ubuntu runner and executes these tests using
the default 17 image; its pre-existing service database is no longer used by the
context test. CI configuration is not recreated or changed.

Tests cover fresh V1-V7, checksums/rerun, fixed seeds and generated role IDs,
explicit baseline on an independent populated identity fixture, unchanged users
and role sequence state, identity metadata/constraint equivalence, refused
implicit adoption, conflicting role IDs, building-only and matched/mismatched
room locations, integrity/deletion restrictions, assignment chronology/history,
initial status uniqueness, rejection reasons, communication metadata, notification
read time and OTP challenge constraints. Application-level authorization,
workflow transitions and OTP delivery belong to later issues.

Restriction tests accept PostgreSQL 17's foreign_key_violation (23503) and
PostgreSQL 18's restrict_violation (23001) for RESTRICT parent operations;
missing-parent inserts/updates must still report foreign_key_violation.
