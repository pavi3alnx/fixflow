# FF-05 location and maintenance category APIs

FF-05 adds building, room and category management using the existing Java 21,
Spring MVC, Security/JWT, Jakarta Validation, Spring Data JPA and PostgreSQL
stack. No dependency, environment-variable or database schema changes are needed.
V1–V7 remain unchanged. The seven category seeds from V4 remain the source of
truth: ELECTRICAL, PLUMBING, HVAC, FURNITURE, NETWORK, CLEANING and OTHER.

## Endpoints and permissions

All endpoints require an enabled, verified user and a valid bearer JWT.
Authorization is enforced both by the HTTP security rules and service method
security. REPORTER and TECHNICIAN may read active/selectable records only.
ADMIN may read all records and create or update them.

| Resource | List | Detail | Create | Replace/update |
| --- | --- | --- | --- | --- |
| Building | `GET /api/buildings` | `GET /api/buildings/{id}` | `POST /api/buildings` | `PUT /api/buildings/{id}` |
| Room | `GET /api/rooms` | `GET /api/rooms/{id}` | `POST /api/rooms` | `PUT /api/rooms/{id}` |
| Category | `GET /api/categories` | `GET /api/categories/{id}` | `POST /api/categories` | `PUT /api/categories/{id}` |

Filter rooms with `/api/rooms?buildingId=123`. There is no duplicate nested
room-list endpoint. Use PUT with `active: false` to deactivate and `active:
true` to reactivate; no separate status endpoint is necessary. There are no
DELETE endpoints. Unsupported paths/methods retain secure default denial.

POST returns 201 with a relative Location header pointing to the detail URL.
GET and PUT return 200 with a DTO. Validation failures return 400, missing
authentication 401, insufficient permission 403, missing or non-visible IDs
404, and duplicate codes or business conflicts 409. Errors use the established
ProblemDetail format. Database conflict details never expose SQL or constraint
internals. Inactive-ID reads return 404 to REPORTER/TECHNICIAN; requesting
`active=false` as either role returns 403. Unknown positive building IDs in
room filters return 404; nonpositive or malformed IDs return 400.

## Write bodies and detail responses

Building POST/PUT:

```json
{"code":"SCIENCE","name":"Science Building","description":"North campus","active":true}
```

Room POST/PUT:

```json
{"buildingId":123,"code":"LAB-01","name":"Physics Lab","description":null,"active":true}
```

Category POST/PUT:

```json
{"code":"LAB_EQUIPMENT","name":"Lab equipment","description":null,"active":true}
```

Codes, required names and `active` must be supplied on both POST and PUT.
`buildingId` is required for rooms. Codes are stripped and uppercased with
`Locale.ROOT`; names/descriptions are stripped and optional blank text becomes
null. Normalization occurs before length validation. Code maximum is 50
characters; building/room names 150, category name 100, description 5000.
Building/category names must be nonblank; room names are optional. PUT is a
full editable-field replacement; omitted optional text is cleared. Omitted
`active` is a validation error rather than an accidental state change.

Building and category DTOs have `id`, `code`, `name`, `description`, `active`.
The existing category detail response retains its original five fields.
Room DTOs have those fields plus `buildingId` and `available`:

```json
{"id":456,"buildingId":123,"code":"LAB-01","name":"Physics Lab","description":null,"active":true,"available":true}
```

`active` is the room's own stored flag; `available` is `room.active &&
building.active`. No entity graphs or internal persistence fields are
serialized. Room queries fetch the parent in the page query, avoiding N+1
lookups while producing DTOs inside read-only service transactions.

## Pagination, filtering and sorting

All three list endpoints accept:

| Parameter | Behavior |
| --- | --- |
| `page` | Zero-based, default 0, range 0–100000 |
| `size` | Default 20, range 1–100 |
| `search` | Optional code/name substring, case-insensitive, maximum 100 characters; literal `%`, `_` and backslash are escaped |
| `active` | Optional boolean; ADMIN omission means all statuses, others default to active-only |
| `sort` | Exactly `field,asc` or `field,desc`; fields: `id`, `code`, `name`, `active`; default `code,asc` |
| `buildingId` | Rooms only; optional positive parent ID |

Sorts append ascending ID as a tie-breaker unless ID is already the selected
field. Unsupported sort fields/directions and invalid pagination are 400.
Example: `GET /api/rooms?buildingId=123&size=20&page=0&sort=code,asc`.
Response structure is independent of Spring Data's internal Page JSON:

```json
{
  "content": [{"id":456,"buildingId":123,"code":"LAB-01","name":"Physics Lab","description":null,"active":true,"available":true}],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

ADMIN room `active` filtering refers to the stored room flag, so an active
room under an inactive building may appear with `available: false`. REPORTER
and TECHNICIAN queries always require both room and parent to be active.
Filter results and counts apply the same visibility rules. This supplies
selectable options for future request forms; FF-06 must also validate active
state transactionally at submission, rather than trusting frontend lists.

## Integrity and lifecycle decisions

- Database uniqueness is the final arbiter, including concurrent writes:
  building code, category code, and `(building_id, room.code)`. Names need not
  be unique. API normalization makes case variants collide on the same code.
- A room's parent is immutable. Moving even an unused room returns 409; create
  a new room and deactivate the old one. This avoids invalidating the existing
  composite maintenance-request foreign key and historical building/room pairs.
- Category codes are immutable stable integration identifiers, including all
  seeded codes. Names, descriptions and active flags remain editable; category
  IDs remain stable. No ML prediction service is implemented.
- Room creation under an inactive building is rejected, even for an inactive
  new room. Activation also requires an active parent. Building-row locks
  serialize those operations against parent updates; database constraints
  remain the final duplicate/foreign-key protection.
- Deactivation never deletes data, changes foreign keys, or cascades flag
  changes to children. Deactivating a building hides all its rooms from
  non-admin reads. Reactivation restores availability only for children whose
  own active flags remain true. Existing request references remain intact.
- Writes use transactions and flush constraint violations before returning.
  List/detail queries use read-only transactions. Existing-database adoption
  and Flyway safety rules remain unchanged; verification uses disposable
  Testcontainers PostgreSQL exclusively.

No new configuration is required. Existing FF-04 secrets and JWT requirements
still apply when running the app; test-profile fixtures keep real credentials
unnecessary for CI. Request creation, lifecycle transitions, user provisioning,
and ML integration remain outside FF-05. There are no ETag/version conflict
controls: concurrent valid edits to the same row are serialized but the later
complete PUT wins.

Run `.\mvnw.cmd -B clean verify` from `backend` with Docker available. The
FF-05 integration suite covers admin CRUD without deletion, active-only reads,
service authorization, pagination/filters/sorting, duplicate and invalid input,
immutable identifiers, concurrent duplicate/parent-state changes, historical
references, and bounded SQL queries for paginated room reads. All existing
authentication and persistence tests remain part of final verification.
