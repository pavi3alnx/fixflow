# FixFlow architecture and local setup

## Current foundation (FF-01)

The backend is a Spring Boot 4.1.1 modular monolith rooted at
`com.fixflow.backend`. Today it contains the application entry point and one
Spring context-loading test. Maven dependencies provide Spring MVC, validation,
Spring Data JPA, the PostgreSQL driver, Lombok, and test support. There are no
feature entities, repositories, controllers, authentication, or implemented
request transitions. FF-02 owns database schema and migrations; FF-01 does not
create or change tables.

The intended system flow is **React → Spring Boot API → PostgreSQL**. React
will present the UI; the API will own validation, authorization, business rules,
and persistence. A future FastAPI ML service may provide classification or
recommendations. ML is optional for request submission: submitting a request
must remain possible when ML is unavailable. These components and integration
rules are planned, not implemented here.

## Package and layer conventions (planned)

Organize code by business feature under `com.fixflow.backend`, for example
`request`, `user`, and `assignment`. Within each feature, use `controller`,
`service`, `repository`, `entity`, and `dto` packages as needed. Keep genuinely
shared configuration and infrastructure outside feature packages; avoid a
single application-wide folder for every controller or service.

| Layer | Responsibility |
| --- | --- |
| Controller | Map HTTP requests and responses, validate DTOs, delegate use cases. |
| Service | Enforce business rules and authorization, coordinate use cases and transaction boundaries. |
| Repository | Encapsulate persistence queries and access through Spring Data JPA. |
| Entity | Represent persistent domain state and database mappings. |
| DTO | Define explicit input/output contracts without exposing persistence entities. |

A typical future request travels from controller through service to repository
and PostgreSQL, with response DTOs returned to the client. Controllers should
not query repositories directly. Schema and entity work belongs to later issues.

## Roles (planned)

| Role | Intended responsibilities |
| --- | --- |
| REPORTER | Submit requests, view permitted requests, supply details, confirm resolution, and request reopening. |
| TECHNICIAN | Work assigned requests, record progress and work notes, and propose resolution. |
| ADMIN | Verify or reject submissions, assign technicians, manage users and operational oversight. |

Authentication, enforcement, visibility rules, and the precise permissions for
each transition remain future implementation work.

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
requirements are design decisions. None of these transitions is implemented.

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

The current resources contain no SQL initialization scripts or explicit
schema-changing startup settings. With PostgreSQL and no migration tooling,
Spring Boot defaults to no Hibernate schema creation. For explicit protection
when verifying an existing database, temporary process-only overrides may be
used:

```powershell
$env:SPRING_JPA_HIBERNATE_DDL_AUTO = 'none'
$env:SPRING_SQL_INIT_MODE = 'never'
.\mvnw.cmd -B clean verify
.\mvnw.cmd spring-boot:run
```

Remove these temporary variables afterward if they were previously unset, or
restore their previous values. These settings prevent ORM DDL and Spring SQL
initialization; they do not implement migrations. Recheck startup scripts and
configuration as the application evolves.

Successful startup should report Tomcat on port 8081 and a successful Hikari
PostgreSQL connection with Hibernate database information identifying the
`fixflow` JDBC URL. A listening port or a 404 at `/` alone does not prove
database connectivity; a root 404 is expected because there is no root route.
The context-loading test uses the configured database, so credentials and a
reachable database are necessary for local tests.

The existing GitHub Actions workflow uses Ubuntu, Temurin Java 21, Maven
`mvn -B clean verify`, and an isolated PostgreSQL 17 service with database
`fixflow`. It supplies disposable test credentials through both `DB_PASSWORD`
and Spring datasource environment variables. Its service health check waits
for PostgreSQL. CI verifies that isolated environment, not the developer's
existing database or locally installed PostgreSQL version. The workflow runs
for pushes to main and pull requests targeting main; it is unchanged by FF-01.
