# FF-08 technician accounts, assignments and resolution

FF-08 completes assignment, acceptance, work, resolution, reporter confirmation
and reopening using the existing Java/Spring MVC, Security/JWT, Validation,
JPA, JDBC, Mail and PostgreSQL stack. No dependency or environment variable is
added. Existing approved university domains, SMTP and authentication settings
apply. All routes require an enabled, verified, non-suspended account.

## Technician accounts

| Endpoint | Permission / result |
| --- | --- |
| POST `/api/admin/technicians` | ADMIN; 201, Location header and safe profile |
| GET `/api/admin/technicians` | ADMIN; paginated profiles |
| GET/PUT `/api/admin/technicians/{id}` | ADMIN; read/update profile and enablement |
| GET `/api/admin/technicians/{id}/workload` | ADMIN; active workload summary |
| GET `/api/technician/workload` | TECHNICIAN; own workload summary |

Create body: `fullName`, `email`, `studentStaffId`, `department`, `password`.
Existing registration validation, normalization, configured university domains,
BCrypt, duplicate constraints and keyed-hash email OTP delivery are reused.
TECHNICIAN is assigned exclusively on the server; public registration remains
REPORTER-only. Duplicates return 409 on this privileged provisioning route.

New accounts are disabled/unverified. The administrator supplies a unique
initial password over HTTPS and coordinates it privately with the recipient
through a separate secure channel. The API never returns/emails the password.
The recipient uses existing verify-email/login endpoints. There are no default
passwords, verification bypass, password reset or forced-password-change flow.
SMTP is mocked in tests; production credentials stay external.

Profile fields: id/fullName/email/studentStaffId/department/enabled/
emailVerified/administrativelyDisabled/updatedAt. No password, OTP or token
internals are returned. Example PUT:

```json
{"fullName":"Example Technician","department":"Facilities","enabled":false,"administrativelyDisabled":true,"expectedUpdatedAt":"2026-11-27T09:00:00.123456"}
```

Email, staff ID and role are immutable here. Activation requires verified email
and no administrative suspension. To resume pending onboarding after a
suspension, set both flags false and use normal OTP verification. Suspended
pending accounts cannot verify/resend their way around an admin decision.
User profile timestamps retain V1's timestamp-without-time-zone format; copy
the exact GET value without adding a UTC suffix.

Deactivation returns 409 while active assignments remain; reassign them first.
Technician-row locks serialize eligibility with deactivation. Historical
episodes survive suspension and no physical deletion route exists. Deactivation
or suspension increments a persisted token version: old JWTs stay revoked even
after reactivation. Login issues the current version. Pre-FF-08 tokens are
version zero until expiry or revocation.

Profile list parameters: page 0–100000 (default 0), size 1–100 (default 20),
optional enabled, literal case-insensitive name/email search (maximum 100),
sort=`field,asc|desc` using id/fullName/email/department. Default fullName,asc
adds ascending ID for ties. Workload returns totalActive and SQL-aggregated
status/official-priority/count buckets. No unsupported availability or
specialization columns are invented.

## Assignment and work APIs

| Endpoint | Permission |
| --- | --- |
| POST `/api/admin/maintenance-requests/{id}/assignments` | ADMIN; create, 201 |
| PATCH `/api/admin/maintenance-requests/{id}/assignment` | ADMIN; reassign, 200 |
| GET `/api/admin/maintenance-requests/{id}/assignments` | ADMIN; paginated episode history |
| GET `/api/maintenance-requests/{id}/assignment` | ADMIN/current TECHNICIAN; current episode |
| PATCH `/api/technician/maintenance-requests/{id}/accept` | Current TECHNICIAN |
| PATCH `/api/technician/maintenance-requests/{id}/start` | Current TECHNICIAN |
| PATCH `/api/technician/maintenance-requests/{id}/hold` | Current TECHNICIAN; reason |
| PATCH `/api/technician/maintenance-requests/{id}/resume` | Current TECHNICIAN |
| PATCH `/api/technician/maintenance-requests/{id}/progress` | Current TECHNICIAN; notes |
| PATCH `/api/technician/maintenance-requests/{id}/resolve` | Current TECHNICIAN; repair notes/evidence |
| PATCH `/api/maintenance-requests/{id}/confirmation` | Owning REPORTER |
| PATCH `/api/maintenance-requests/{id}/reopening` | Owning REPORTER; reason |

The work queue reuses GET `/api/maintenance-requests` with existing status,
priority, location/category/date/search/pagination/sorting conventions. SQL
ownership predicates restrict both results and totals to current assignments;
caller-supplied technician IDs cannot widen access. Joined catalog DTOs avoid
N+1 reads; there is no duplicate queue route.

All mutations require the exact current request updatedAt as expectedUpdatedAt
and return the existing RequestResponse DTO, including the next concurrency
token. New assignment Location points to the current-assignment GET. Example:

```json
{"technicianId":42,"expectedUpdatedAt":"2026-11-27T09:00:00.123456Z","reason":"Replacement specialist required"}
```

Assignment requires VERIFIED, no current episode and an eligible TECHNICIAN.
Reassignment accepts ASSIGNED/IN_PROGRESS/ON_HOLD, requires a different eligible
technician and a stripped reason of 5–2000 characters. The old episode records
endedAt, REASSIGNED and its reason; the new one is unaccepted/unstarted.
ASSIGNED→ASSIGNED advances the request token without fake status history;
started/held work returns to ASSIGNED with a status event. Actor/timestamps
come from the server, not request fields.

Accept/start/resume/confirmation need expectedUpdatedAt. Optional notes are
recorded for start/resume/confirmation; acceptance records only its timestamp.
Hold/reopening need notes of 5–2000 characters; progress needs 5–10000.
Work notes append timestamped lines to repair notes, preserving earlier lines,
with a 20000-character cumulative limit. Confirmation notes are capped at 2000.

Resolution example:

```json
{"expectedUpdatedAt":"2026-11-27T09:00:00.123456Z","repairNotes":"Replaced defective component and tested operation","evidenceAttachmentIds":[91,92]}
```

Repair notes require 5–10000 characters. Up to ten distinct evidence IDs are
optional. Each must already be this technician's REPAIR_EVIDENCE attachment
for this request, created no earlier than this assignment and never linked to
another episode. Durable links commit with resolution. FF-08 has no file
upload/storage endpoint; references can be supplied when the FF-09 attachment
workflow creates them. Arbitrary paths/URLs are not accepted as evidence.

Episode views contain id/requestId/technicianId, assignment/acceptance/start/
resolution/end timestamps, endReason, own work notes and evidence IDs. ADMIN
history wraps them with assignedBy and reassignmentReason, ordered by
assignedAt then ID. Its page/size limits match existing history APIs and links
are fetched in one batch. Ended episode history and private administrative
reasons are not exposed to technicians. After reassignment/resolution the
existing current-assignment read policy removes the former technician's access;
resolution still returns its authorized action response.

## Complete implemented lifecycle

| Edge/action | Actor / prerequisites |
| --- | --- |
| OPEN → VERIFIED/REJECTED | Existing ADMIN review; rejection reason |
| VERIFIED → ASSIGNED | ADMIN creates eligible assignment |
| Accept, ASSIGNED unchanged | Current TECHNICIAN, once before start |
| ASSIGNED → IN_PROGRESS | Current TECHNICIAN, accepted episode |
| IN_PROGRESS → ON_HOLD | Current TECHNICIAN, reason |
| ON_HOLD → IN_PROGRESS | Current TECHNICIAN resumes started episode |
| Progress, status unchanged | Current TECHNICIAN, IN_PROGRESS/ON_HOLD |
| IN_PROGRESS → RESOLVED | Current TECHNICIAN, started episode and repair notes |
| RESOLVED → CONFIRMED | Owning REPORTER, current resolution proof/history |
| CONFIRMED → CLOSED | Existing ADMIN closure, latest completed episode |
| RESOLVED → REOPENED | Owning REPORTER or existing ADMIN reopening, reason/proof |
| REOPENED → VERIFIED | Existing ADMIN reassessment before fresh assignment |
| ASSIGNED/IN_PROGRESS/ON_HOLD → ASSIGNED | ADMIN reassignment; acceptance resets |

Resolution stores resolvedAt=endedAt, RESOLVED endReason and durable repair
notes, satisfying database completion checks. Confirmation requires the latest
completed assignment and matching latest RESOLVED history; prior cycles cannot
justify confirming unfinished work. Reopening/reassignment preserve completed
episodes and evidence links. CLOSED/REJECTED remain terminal. No cancellation,
deletion, generic technician status patch or generic ADMIN assignment bypass
is added. The existing centralized policy has actor/use-case-specific checks.

## Transactions, conflicts and V8

Workflow writes lock the request first, check ownership/state/preconditions
inside that transaction, then lock the selected technician for eligibility.
Deactivation locks only the user and checks active work without request locks,
avoiding reverse lock order. Assignment-only acceptance/progress/reassignment
also advance the request token. Profiles have their own expectedUpdatedAt.
Stale writes return 409. Unknown/unowned resources return 404; ended owned
episodes and invalid workflow actions return 409. Invalid bodies return 400,
missing/invalid authentication 401, wrong roles 403; unsupported routes stay
denied at HTTP and service layers.

Request, assignment, status history and JDBC evidence links share one datasource
and transaction. Required persistence failure rolls everything back. Status
history stays append-only with public/ADMIN separation; same-state assignment
events are represented by episode metadata, never fake status transitions.
Sensitive DTO string representations are redacted.

V8 adds only missing persistence: user suspension/token-version columns,
assignment reassignment reason and assignment/evidence links with restricted
foreign keys and unique attachment usage. V1–V7 are unchanged. Legacy flags and
token versions default false/zero; old reassignment reasons remain nullable.
Migration tests compare original identity columns/data/constraints/indexes/
sequences and verify each explicit addition. No developer database is migrated,
baselined, reset or otherwise changed for this task.

Run `.\mvnw.cmd -B clean verify` from backend with Docker. Tests use disposable
PostgreSQL, mock SMTP, cover onboarding/suspension/token revocation, roles,
full lifecycle/evidence/history/rollback and independent-transaction races for
assignment, reassignment, start, resolution, confirmation and deactivation.
FF-09 implements [uploads, comments and in-app notifications](communication-photos-notifications.md),
including episode-bound repair photos for the evidence references above.
Password reset, interfaces and ML remain deferred.
