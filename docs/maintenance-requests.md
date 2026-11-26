# FF-06 maintenance request submission and tracking

FF-06 adds request submission, permitted reporter edits, scoped listings,
details and status history using the existing Spring MVC, Security/JWT,
Validation, Spring Data JPA and PostgreSQL stack. No schema, dependency or
environment-variable changes are required. Existing Flyway migrations and
database safety settings remain unchanged.

## Endpoints and permissions

All endpoints require an enabled, verified account with a valid bearer JWT.

| Endpoint | Behavior | Access |
| --- | --- | --- |
| `POST /api/maintenance-requests` | Submit; 201 with Location header and request DTO | REPORTER |
| `GET /api/maintenance-requests` | Paginated, filtered request DTOs | REPORTER: own; ADMIN: all; TECHNICIAN: current assignments |
| `GET /api/maintenance-requests/{id}` | Request details; 200 | Same visibility as listing |
| `GET /api/maintenance-requests/{id}/history` | Paginated chronological status history; 200 | Same visibility as details |
| `PUT /api/maintenance-requests/{id}` | Replace editable fields; 200 | Owning REPORTER, OPEN status only |

There is no duplicate `/my` endpoint, DELETE endpoint, status-change endpoint,
admin edit route, or assignment API. Other paths and methods remain denied.
ADMIN can retrieve requests for review, but review decisions, administrative
priority changes and status transitions belong to FF-07. TECHNICIAN cannot
submit/edit requests or browse unassigned requests. Current assignment access
uses the existing `ended_at IS NULL` rule; assignment creation remains FF-08.

Service method security enforces roles independently of HTTP rules. A shared
`RequestAccess` specification applies ownership/assignment predicates directly
in database queries for detail, listing, counts and history authorization.
Other reporters' IDs and inaccessible IDs produce the same 404 as missing
resources. Supplying a `reporterId` query parameter cannot widen access.

## Submission and update contracts

POST and PUT accept the same dedicated editable-field contract:

```json
{
  "title": "Flickering light in lab",
  "description": "The ceiling light flickers continuously.",
  "buildingId": 12,
  "roomId": 34,
  "categoryId": 1,
  "suggestedPriority": "HIGH"
}
```

`title` and `description` are stripped before validation, must be nonblank,
and have maximum lengths of 200 and 10000 characters respectively.
`buildingId` and `categoryId` are required positive IDs. `roomId` is optional
or null for building-wide problems; if supplied it must be positive, exist,
be active and belong to the chosen building. The selected building and
category must also exist and be active. A room whose parent building is
inactive is unavailable. Suggested priority is required and accepts exactly
`LOW`, `MEDIUM`, `HIGH` or `URGENT`.

The reporter comes from the authenticated user, never a body field. Status,
administrative `priority`, IDs, timestamps, assignment and audit fields are
not writable DTO properties; unexpected body fields cannot modify them.
Only `suggestedPriority` represents the reporter's assessment.

The existing PostgreSQL identity ID is the unique request identifier. No
separate human-readable reference is required by the issue or schema, so no
count-based reference generator or new column is introduced. Creation starts
at `OPEN`. Administrative priority is server-initialized to `MEDIUM`,
independent of the reporter's suggestion, until the future review workflow.
Creation and update timestamps initially match. Creation uses the shared UTC
clock at PostgreSQL microsecond precision; managed edits use the established
entity `@PreUpdate` timestamp convention.

Example response (Location: `/api/maintenance-requests/789`):

```json
{
  "id": 789,
  "reporterId": 56,
  "title": "Flickering light in lab",
  "description": "The ceiling light flickers continuously.",
  "building": {"id":12,"code":"SCIENCE","name":"Science Building","active":true},
  "room": {"id":34,"code":"LAB-01","name":"Physics Lab","active":true},
  "category": {"id":1,"code":"ELECTRICAL","name":"Electrical","active":true},
  "suggestedPriority": "HIGH",
  "priority": "MEDIUM",
  "status": "OPEN",
  "createdAt": "2026-11-26T09:00:00Z",
  "updatedAt": "2026-11-26T09:00:00Z"
}
```

`room` is null for building-only requests. References show current catalog
names and flags, rather than snapshots of names at submission. Stored foreign
keys preserve identity after renames and deactivation. Inactive references do
not prevent historical detail/list/history reads. These flags describe
catalog state, not a requirement that an existing request be moved.

PUT replaces all editable fields; include every required field. Omitting
`roomId` clears the room. Ownership, original `createdAt`, status, effective
priority and initial history remain unchanged. Every PUT revalidates active
references, so editing an OPEN request whose selections became inactive
requires selecting available replacements. Other statuses return 409.
Edits do not create status history because they do not change status. There
is no general content audit trail or optimistic version/ETag contract in
FF-06; valid concurrent edits serialize and the later full replacement wins.

## Listings, search, pagination and sorting

The response uses the existing stable `PageResponse` shape:

```json
{"content":[],"page":0,"size":20,"totalElements":0,"totalPages":0}
```

| Parameter | Rule |
| --- | --- |
| `page` | Zero-based, default 0, range 0–100000 |
| `size` | Default 20, range 1–100 |
| `search` | Case-insensitive literal substring of title or description; maximum 100 characters |
| `status` | One of the existing RequestStatus names, e.g. `OPEN`, `VERIFIED`, `CLOSED` |
| `buildingId`, `categoryId` | Optional positive IDs; unknown positive IDs yield empty results |
| `submittedFrom` | Inclusive creation-time lower bound, ISO-8601 instant |
| `submittedTo` | Exclusive creation-time upper bound, ISO-8601 instant |
| `sort` | Exactly `field,asc` or `field,desc`; default `createdAt,desc` |

Sort fields are `id`, `title`, `createdAt`, `updatedAt`, `status`, `priority`
and `suggestedPriority`. ID in the selected direction breaks ties, unless ID
is already the selected field. Priority/status sorting is lexical by enum
name, not severity or workflow order. `%`, `_` and backslash in searches are
escaped rather than interpreted as LIKE controls. Date bounds, when both
present, must satisfy `submittedFrom < submittedTo`. Example:

`GET /api/maintenance-requests?status=OPEN&buildingId=12&size=20&sort=createdAt,desc`

Ownership remains mandatory with every filter and affects totals and page
contents alike. Queries page in PostgreSQL and fetch the needed catalog
references together, avoiding in-memory visibility filtering and N+1 reads.

## Status history

History uses the same PageResponse metadata with `page`/`size` bounds above,
ordered by `changedAt,asc`, then `id,asc`. It has no client-controlled sort.

```json
{
  "content":[{"id":101,"previousStatus":null,"newStatus":"OPEN","changedAt":"2026-11-26T09:00:00Z"}],
  "page":0,"size":20,"totalElements":1,"totalPages":1
}
```

The initial entry records null → OPEN, the authenticated reporter as the
database audit actor, and the same creation timestamp as the request. Both
rows commit in one transaction or neither persists. PostgreSQL enforces one
initial entry per request and valid history references. Public tracking DTOs
omit actor personal information, reason/admin notes, credentials and security
state. Future workflows will write further transitions; FF-06 only reads
them and records initial creation.

## Errors, transactions and verification

- 400: invalid content, enum, paging/sort/date/filter values, or room/building
  mismatch. Invalid fields use existing validation ProblemDetail responses.
- 401: missing, invalid, expired JWT or disabled/unverified account.
- 403: role cannot perform the requested operation.
- 404: nonexistent references, missing request, or request outside visibility.
- 409: inactive selections, non-OPEN edit, or database integrity conflict.

Example concealed-resource response:

```json
{"type":"about:blank","title":"Not Found","status":404,"detail":"The requested resource was not found."}
```

Submission and editing lock the selected building, optional room, then category
while checking availability, using the existing FF-05 lock methods/order.
This serializes those checks against management deactivation until commit.
Owned updates also lock the request before checking OPEN status. Foreign keys
remain the final location/category consistency protection. Ordinary database
constraint failures return sanitized conflicts. No applied migration or
existing database is altered for verification. Existing indexes already cover
reporter/date, status/date, location, category and history/date query patterns;
no speculative indexes are added for unmeasured search load.

Run `.\mvnw.cmd -B clean verify` from `backend` with Docker. Tests use the
existing explicit test profile and disposable Testcontainers PostgreSQL, never
the developer database. Coverage includes spoofed ownership/workflow fields,
all visibility paths and assignment revocation, request/history rollback,
concurrent submissions/deactivation, validation, permitted updates, filters,
historical reads and bounded SQL query counts. Existing FF-01 through FF-05
tests remain part of final verification. No real SMTP or production secrets
are required.

Status administration, assignment/resolution, comments, photos, notifications,
dashboards and ML prediction remain deferred to their respective issues.
