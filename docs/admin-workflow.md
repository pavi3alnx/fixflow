# FF-07 administrative request workflow

FF-07 adds ADMIN-only verification, priority and status operations, centralized
transition policy, privileged audit reads and an official-priority list filter.
It reuses Spring MVC, Security/JWT, Jakarta Validation, JPA and PostgreSQL.
There are no new dependencies, configuration settings or migrations.

## HTTP contracts

All routes below require an enabled, verified ADMIN and a valid bearer token.
The distinct admin namespace preserves the FF-06 public request contracts and
their ownership rules; no alternate public workflow routes are opened.

| Endpoint | Body / purpose |
| --- | --- |
| `PATCH /api/admin/maintenance-requests/{id}/verification` | Review an OPEN request; select VERIFIED or REJECTED |
| `PATCH /api/admin/maintenance-requests/{id}/priority` | Change official priority on a permitted state |
| `PATCH /api/admin/maintenance-requests/{id}/status` | Perform supported post-review transitions |
| `GET /api/admin/maintenance-requests/{id}/history` | Privileged chronological status audit; page/size supported |

Writes return the existing RequestResponse DTO with HTTP 200. Obtain the
current request from `GET /api/maintenance-requests/{id}` and copy its exact
`updatedAt` value into the required `expectedUpdatedAt` field. After a
successful write, use the returned timestamp for the next action.

Verification example:

```json
{"status":"VERIFIED","reason":"Location and problem reviewed","expectedUpdatedAt":"2026-11-26T09:00:00.123456Z"}
```

Rejection example:

```json
{"status":"REJECTED","reason":"Duplicate of an existing request","expectedUpdatedAt":"2026-11-26T09:00:00.123456Z"}
```

Priority example:

```json
{"priority":"URGENT","expectedUpdatedAt":"2026-11-26T09:00:00.123456Z"}
```

Status example, requiring an already completed resolution assignment:

```json
{"status":"REOPENED","reason":"The same fault returned after repair","expectedUpdatedAt":"2026-11-26T09:00:00.123456Z"}
```

Rejection and reopening reasons require at least 5 characters after stripping;
all optional notes/reasons have a maximum of 2000 characters. Other transition
notes are optional. Client actor IDs, timestamps, roles, reporter IDs and
other unexpected fields never control audit/ownership data. Actor comes from
the authenticated principal and timestamps come from the server.

## Transition policy

The following is the complete FF-07 executable transition matrix. Every other
pair, including same-state transitions, is rejected with 409.

| Current | Target | Endpoint / conditions |
| --- | --- | --- |
| OPEN | VERIFIED | verification; ADMIN review |
| OPEN | REJECTED | verification; meaningful reason required |
| RESOLVED | REOPENED | status; meaningful reason and completed resolution episode required |
| REOPENED | VERIFIED | status; reassessment before reassignment; completed resolution episode required |
| CONFIRMED | CLOSED | status; completed resolution episode required |

All these actions require no current assignment (`ended_at IS NULL`). For
post-resolution actions, a historical assignment with `end_reason=RESOLVED`
and nonnull `resolved_at` is required. Assignment constraints also require
completion timestamps and repair notes. A fabricated status without that
evidence cannot be advanced. FF-07 does not create, finish or invalidate
assignment episodes. Reopening is an ADMIN review action; reassessment returns
to VERIFIED rather than bypassing a fresh assignment.

The following lifecycle edges are now implemented by [FF-08 dedicated use cases](technician-workflow.md),
while the generic ADMIN status endpoint continues to reject them:

| Transition | Responsible action / status |
| --- | --- |
| VERIFIED → ASSIGNED | ADMIN assignment operation, FF-08; must create an assignment |
| ASSIGNED → IN_PROGRESS | Assigned TECHNICIAN work operation, FF-08 |
| IN_PROGRESS → RESOLVED | Assigned TECHNICIAN resolution operation, FF-08; must record/finish repair episode |
| IN_PROGRESS → ON_HOLD | Assigned TECHNICIAN hold operation, FF-08; reason required |
| ON_HOLD → IN_PROGRESS | Assigned TECHNICIAN resume operation, FF-08; retain current assignment |
| RESOLVED → CONFIRMED | Owning REPORTER confirmation action, FF-08; not an admin override |

Reserved edges are denied by FF-07's generic status endpoint even when an
assignment exists. No workflow can skip verification. CLOSED and REJECTED are
terminal. Cancellation has no separate enum or endpoint: an ADMIN may decline
an OPEN request via reasoned REJECTED; reporters cannot silently delete/cancel
submitted records. No reopening from CLOSED/REJECTED is permitted. These are
explicit rules, not newly invented status names. FF-08's dedicated confirmation
and assignment/work actions complete the primary lifecycle without broadening
the generic ADMIN endpoint.

## Priority, listings and audit

Official priority accepts LOW, MEDIUM, HIGH or URGENT and never overwrites the
reporter's separate `suggestedPriority`. Changes are allowed in OPEN, VERIFIED,
ASSIGNED, IN_PROGRESS, ON_HOLD and REOPENED; RESOLVED, CONFIRMED, CLOSED and
REJECTED reject priority writes. A fresh same-priority write in an editable
state returns 200 without timestamp/history changes. Stale writes still
return 409 even if the requested value now matches.

Reuse `GET /api/maintenance-requests?priority=URGENT&status=OPEN` for review.
The new optional `priority` filter uses SQL and the existing scope, pagination,
sorting and search rules. REPORTER/TECHNICIAN visibility is never widened.
There is no redundant admin list route.

Every successful status change appends previous/new status, authenticated actor
ID, reason/note and server timestamp to `status_history` in the same transaction
as the request update. The history timestamp matches the request's monotonic
persisted update timestamp. A failed history insert rolls back state and timestamp.
The initial FF-06 OPEN history is preserved. No history write/update/delete
API is exposed. Ordering remains `changedAt,asc`, then `id,asc`.

The existing public `/api/maintenance-requests/{id}/history` response retains
its four tracking fields. The new ADMIN-only history returns PageResponse
with entries containing `id`, `previousStatus`, `newStatus`, `actorId`, `reason`
and `changedAt`. Default page is 0, size 20, maximum size 100; maximum page is
100000. The privileged view is separate because administrative notes/actor
metadata must not leak into reporter/technician tracking responses.

Priority-only changes update official priority and `updatedAt` but do not
invent a status transition. Issue #7 does not require a durable priority-change
ledger, and the existing schema has none. No priority audit table is added;
historical priority actors/old values are therefore not retained. This is an
explicit limitation. Status-transition actors/reasons are fully persisted.

## Concurrency, errors and verification

Both administrative writes and reporter OPEN edits lock the same request row
with PESSIMISTIC_WRITE. State validation happens after acquiring the lock.
Admin writes additionally compare required `expectedUpdatedAt` with the
locked persisted timestamp. A stale client receives 409 and must reload;
no automatic retry overwrites another operation. Updated timestamps use UTC
microsecond precision and strictly advance on each actual managed change, even
within one clock tick. This avoids a version column while retaining a stable
client precondition. Same-value no-ops do not advance the timestamp.

If a reporter edit wins first, a concurrent admin review using the old token
conflicts. If verification wins first, the reporter's OPEN-only edit conflicts.
Reporter DTOs still cannot change status/official priority. Concurrent priority
or status actions using the same token have one winner and one 409. Future
assignment writers must lock this same request row before checking status or
changing assignment state. Only the target request is locked by admin writes;
catalog rows are not unnecessarily locked. Existing reporter/catalog locking
and FK protections remain intact.

FF-06 reporter edits retain their prior replacement contract and do not require
an admin concurrency token; concurrent reporter content edits still use the
existing serialized last-replacement-wins behavior. ADMIN stale writes are
rejected regardless of whether an admin action or reporter edit changed the row.

400 covers invalid input, verification targets other than VERIFIED/REJECTED,
or missing/short/oversized required reasons. 401 means authentication failure;
403 means wrong role; 404 means a missing request; 409 covers invalid/repeated
transitions, unavailable assignment evidence, restricted priority states,
stale timestamps, lock conflicts and database integrity conflicts. Existing
ProblemDetail conventions sanitize internal SQL/provider errors. Notes are
redacted in DTO string representations and are not logged by application code.

Run `.\mvnw.cmd -B clean verify` from `backend` with Docker. Tests use disposable
PostgreSQL databases and independent transactions for races, cover every
FF-07 allowed/denied status pair, actor spoofing, audit visibility, rollback,
priority/no-op behavior and reporter/admin races, and retain the 95-test
baseline. Applied migrations and the developer database remain untouched.
