# FixFlow architecture and local setup

## Current foundation (FF-01 through FF-06)

The backend is a Spring Boot 4.1.1 modular monolith rooted at
`com.fixflow.backend`. It contains the application entry point, a Spring context
test, and focused database migration tests. Maven dependencies provide Spring
MVC, validation, Spring Data JPA, the PostgreSQL driver, Lombok, Flyway, and test
support. FF-02 adds seven versioned SQL migrations for the 12 domain tables;
see [the ER diagram](er-diagram.md) and [migration procedure](database-migrations.md).
FF-03 maps all 12 domain tables and supplies Spring Data repositories in
`identity`, `location`, `category`, `request`, `assignment`, `comment`,
`attachment`, and `notification`. `common.api` owns sanitized ProblemDetail
errors. The representative `GET /api/categories/{id}` follows controller to
transactional service to repository and returns a public response DTO.
`RequestDetails` supplies validated reporter input for FF-06 submission and edits.
FF-04 implements registration, email OTP verification, login, JWT authentication,
and role/ownership authorization foundations; see [authentication and API contracts](authentication.md).
FF-05 extends `location` and `category` with ADMIN management APIs and paginated,
active-only reads for REPORTER/TECHNICIAN; see [location/category API contracts](location-category-management.md).
FF-06 adds reporter submission/OPEN edits, scoped tracking APIs, and atomic
initial status history; see [maintenance request API contracts](maintenance-requests.md).
Administrative request transitions remain outside this foundation. Existing databases require reviewed explicit
adoption before startup; implementation does not migrate the local database.

The intended system flow is **React → Spring Boot API → PostgreSQL**. React
will present the UI; the API will own validation, authorization, business rules,
and persistence. A future FastAPI ML service may provide classification or
recommendations. ML is optional for request submission: submitting a request
must remain possible when ML is unavailable. These components and integration
rules are planned, not implemented here.

## Package and layer conventions

Organize code by business feature under `com.fixflow.backend`, for example
`request`, `identity`, and `assignment`. Small features keep their classes
together; introduce layer subpackages within a feature as its size warrants. Keep genuinely
shared configuration and infrastructure outside feature packages; avoid a
single application-wide folder for every controller or service.

| Layer | Responsibility |
| --- | --- |
| Controller | Map HTTP requests and responses, validate DTOs, delegate use cases. |
| Service | Enforce business rules and authorization, coordinate use cases and transaction boundaries. |
| Repository | Encapsulate persistence queries and access through Spring Data JPA. |
| Entity | Represent persistent domain state and database mappings. |
| DTO | Define explicit input/output contracts without exposing persistence entities. |

A request travels from controller through service to repository
and PostgreSQL, with response DTOs returned to the client. Controllers should
not query repositories directly. Services own transactions and map entities to DTOs before returning to controllers.
Open-in-view is disabled; HTTP responses never expose entities or secret hashes.

## Roles (planned)

| Role | Intended responsibilities |
| --- | --- |
| REPORTER | Submit requests, view permitted requests, supply details, confirm resolution, and request reopening. |
| TECHNICIAN | Work assigned requests, record progress and work notes, and propose resolution. |
| ADMIN | Verify or reject submissions, assign technicians, manage users and operational oversight. |

FF-04 enforces authenticated role access. FF-06 applies its shared ownership
policy to request details, lists and history: REPORTER owns, ADMIN reviews,
and TECHNICIAN reads current assignments. Workflow transitions remain future work.

## Request lifecycle (planned)

The primary path is:

`OPEN → VERIFIED → ASSIGNED → IN_PROGRESS → RESOLVED → CONFIRMED → CLOSED`

OPEN means submitted; VERIFIED means accepted as valid; ASSIGNED means a
technician has been selected; IN_PROGRESS means work has begun; RESOLVED means
the technician reports completion; CONFIRMED means the reporter accepts the
resolution; CLOSED means the workflow is finalized.

Proposed branches, subject to design decisions:

- `OPEN → REJECTED` for invalid submissions. Whether verified requests can be
  rejected, who can reject, and whether rejection is terminal are undecided.
- `ASSIGNED / IN_PROGRESS → ON_HOLD` when work is blocked. Allowed source
  states, who can pause/resume, and whether resume restores the prior state
  are undecided.
- `RESOLVED / CONFIRMED / CLOSED → REOPENED` when a problem persists.
  Eligible source states, actor permissions, time limits, and whether REOPENED
  returns to VERIFIED, ASSIGNED, or IN_PROGRESS are undecided.

Confirmation requirements, automatic closure, reassignment, and audit/reason
requirements are design decisions. FF-06 records initial creation as OPEN;
none of the later transitions is implemented.

## Local prerequisites and configuration

Use Java 21 and the checked-in Maven Wrapper. Wrapper tooling is 3.3.4 and its
configured Maven distribution is 3.9.16. A local PostgreSQL instance must expose
the existing `fixflow` database at `localhost:5432`, with user `postgres`.
The checked-in configuration sets `server.port=8081`.

The application explicitly reads `DB_PASSWORD` through
`spring.datasource.password=${DB_PASSWORD}` with no credential fallback.
Set it privately in the same PowerShell session that launches Maven. Do not
store a real password in tracked files, command history, or chat. PowerShell 7
can populate it using a hidden prompt:

```powershell
$env:DB_PASSWORD = Read-Host 'Local PostgreSQL password' -MaskInput
cd C:\Users\hp\Downloads\filxflow\backend
.\mvnw.cmd -v
.\mvnw.cmd -B clean verify
.\mvnw.cmd spring-boot:run
```

Before `spring-boot:run`, also supply the required JWT/OTP keys, approved university
domains and SMTP settings listed in [authentication configuration](authentication.md).
Automated tests use their explicit test profile and require none of these real secrets.

On Windows PowerShell 5.1, use an appropriate private environment-variable
setup or a secure prompt conversion; `-MaskInput` requires PowerShell 7.
Environment variables keep credentials out of source control and let each
environment supply its own configuration. Spring Boot does not automatically
load a `.env` file.

Spring Boot also supports standard environment overrides, including
`SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`,
`SPRING_DATASOURCE_PASSWORD`, and `SERVER_PORT`. These are framework bindings,
not additional custom variables. An explicit `SPRING_DATASOURCE_PASSWORD`
overrides the configured password property. `JAVA_HOME` selects the JDK used
by the wrapper; confirm its Java version with `mvnw.cmd -v`.

## Verification and database safety

Flyway now owns schema changes on application startup. Automatic baselining
and clean are disabled; Hibernate validates the migrated schema (`ddl-auto=validate`)
without generating DDL. Spring SQL initialization is disabled.
Follow the [existing-database adoption procedure](database-migrations.md) before
starting against an existing schema. Hibernate/SQL-init settings alone do not
prevent Flyway migrations. For a connectivity-only run that must not migrate,
use these temporary process-only overrides:

```powershell
$env:SPRING_JPA_HIBERNATE_DDL_AUTO = 'none'
$env:SPRING_SQL_INIT_MODE = 'never'
$env:SPRING_FLYWAY_ENABLED = 'false'
.\mvnw.cmd -B clean verify
.\mvnw.cmd spring-boot:run
```

Remove these temporary variables afterward if they were previously unset, or
restore their previous values. These settings prevent ORM DDL, Spring SQL initialization, and Flyway startup
migrations for that process; they do not implement database adoption. Recheck startup scripts and
configuration as the application evolves.

Successful startup should report Tomcat on port 8081 and a successful Hikari
PostgreSQL connection with Hibernate database information identifying the
`fixflow` JDBC URL. A listening port or an HTTP response alone does not prove
database connectivity. FF-04 denies unspecified routes, including `/`;
unauthenticated requests receive 401 rather than the earlier foundation's 404.
Automated tests override the datasource with disposable Testcontainers databases;
they require Docker and do not use the local database or DB_PASSWORD. The default
PostgreSQL image is 17; see the migration guide for verification against 18.

The existing GitHub Actions workflow uses Ubuntu, Temurin Java 21, Maven
`mvn -B clean verify`, and an isolated PostgreSQL 17 service with database
`fixflow`. It supplies disposable test credentials through both `DB_PASSWORD`
and Spring datasource environment variables. Its service health check waits
for PostgreSQL. FF-02 tests now use their own disposable PostgreSQL 17 container
rather than this pre-existing service. CI does not verify the developer's existing database
or locally installed PostgreSQL version. The workflow runs
for pushes to main and pull requests targeting main; it is unchanged by FF-02.

## FF-03 persistence conventions

All IDs use identity generation and relationships are unidirectional lazy
many-to-one references without cascading deletes. Enums are stored by name.
The required request building writes `building_id` once; the optional room
writes only `room_id`. PostgreSQL's existing composite foreign key enforces that
the room belongs to the building, including after updates.

Identity user timestamps use `LocalDateTime` for V1's timestamp without time
zone; other timestamp columns use `Instant` for timestamptz. Entity initializers
supply the migration defaults when JPA explicitly inserts those columns
(false flags, active=true, OPEN status, zero attempts, and current timestamps).
`@PreUpdate` refreshes `updated_at` on managed entity updates. Bulk/native writes
must manage timestamps explicitly. Database defaults still apply to inserts
that omit the columns. Historical rows have no cascade-removal mapping.

`PersistenceFoundationTests` reuses FF-02's disposable TestDatabase to validate
the schema, query a seeded category, round-trip both request location forms,
and verify transaction rollback. `ApiFoundationTests` exercises DTO validation,
public DTO responses, malformed input, and sanitized 400/404/409 errors through
MockMvc. Its POST controller exists only in test sources. Run from `backend`:
`.\mvnw.cmd -B clean verify` with Docker available. V1-V7 remain unchanged.
