# FF-09 comments, photos and in-app notifications

FF-09 reuses Spring MVC/Security/JWT, Validation, JPA/JDBC, PostgreSQL and the
existing request visibility/workflow infrastructure. No new dependency is added.
Cloudinary authenticated assets implement Issue #9's external storage contract;
an explicit local provider supports development and temporary-directory tests.

## REST contracts and permissions

| Endpoint | Contract |
| --- | --- |
| POST `/api/maintenance-requests/{id}/comments` | JSON `{"body":"Plain text message"}`; 201 |
| GET `/api/maintenance-requests/{id}/comments` | Chronological paginated shared conversation |
| POST `/api/maintenance-requests/{id}/attachments` | Multipart `file` and `purpose`; 201 metadata/Location |
| GET `/api/maintenance-requests/{id}/attachments` | Chronological paginated metadata |
| GET `/api/maintenance-requests/{id}/attachments/{attachmentId}/download` | Authorized image bytes |
| GET `/api/notifications` | Recipient's paginated notifications; optional `unread=true` |
| GET `/api/notifications/unread-count` | `{"unreadCount":3}` |
| PATCH `/api/notifications/{id}/read` | Recipient-only, idempotent 204; no body required |
| PATCH `/api/notifications/read-all` | Recipient-only, idempotent 204 |

All endpoints require active verified accounts and valid, non-revoked JWTs.
REPORTER may access their own requests, TECHNICIAN their current assignments,
and ADMIN all requests. Former technicians lose conversation/photo access when
their assignment ends. Missing/unowned request or cross-request attachment IDs
return the same concealed 404. There are no public provider URLs or file paths.
HTTP and service-level role checks both apply; other routes remain denied.

Comments and metadata lists use page 0–100000 (default 0), size 1–100 (default
20), fixed createdAt/ID ascending ordering. Notifications use createdAt/ID
descending. Responses use the existing `content/page/size/totalElements/totalPages`
envelope. Invalid paging returns 400. Existing request lists remain the work
queue; no duplicate request/conversation route is introduced.

## Shared comments

Body is stripped, required, nonblank and at most 5000 characters. Comments
are append-only plain text, with server-derived author ID/current role and
timestamp. HTML is stored as text; future React clients must render it as text,
never insert it as HTML. No comment edit/delete API is required by Issue #9.
CLOSED/REJECTED conversations are read-only. Other authorized participants may
comment without changing request expectedUpdatedAt.

The issue and existing schema have no internal-comment mode. Comments are
shared with authorized participants; unknown input properties, including
`internal`, `visibility`, author or audit fields, are rejected with 400 rather
than silently publishing something a client intended to make private. Existing
administrative status/assignment reasons retain their separate privileged APIs.
There are no comment attachments or notification previews that could leak them.

Example response:

```json
{"id":91,"authorId":42,"authorRole":"REPORTER","body":"The light still flickers.","createdAt":"2026-11-27T09:00:00Z"}
```

Author role reflects the current author record; no author email/password/OTP
data is exposed. Fetch graphs avoid per-comment author/role queries.

## Secure uploads and resolution evidence

Multipart purpose is `ISSUE_PHOTO` or `REPAIR_EVIDENCE`. A REPORTER may add issue
photos only to their own OPEN request. The currently assigned TECHNICIAN may
add repair evidence during ASSIGNED, IN_PROGRESS or ON_HOLD. ADMIN reads photos
but does not upload on behalf of participants. Unknown purposes return 400;
inappropriate roles/purposes return 403, unavailable states/count limits 409.

Only valid JPEG (`image/jpeg`) and PNG (`image/png`) content is accepted. Actual
signatures must match MIME, ImageIO must decode the image, and header dimensions
must pass limits before full decoding. Images are re-encoded, removing EXIF,
other metadata and trailing executable/HTML payloads. Empty, corrupt, SVG, GIF,
executable and MIME-spoofed uploads return 415. Original filename extensions do
not determine the content type or destination. Oversized bytes/dimensions/pixels
return 413. Filenames are sanitized display metadata; server UUID keys are used
for storage and canonical photo-ID filenames are used for downloads.

Example metadata:

```json
{"id":105,"requestId":789,"uploadedBy":42,"purpose":"REPAIR_EVIDENCE","assignmentId":18,"filename":"repair.png","mediaType":"image/png","sizeBytes":12500,"createdAt":"2026-11-27T09:00:00Z"}
```

Metadata excludes storage keys, absolute paths, provider URLs and credentials.
Downloads return canonical image content type, `Content-Disposition: attachment`,
`X-Content-Type-Options: nosniff`, and `Cache-Control: no-store`. The backend
rechecks request access and attachment/request association before retrieving
bytes; downloads never redirect clients to the provider. Authorization is
point-in-time; an already authorized response may finish during reassignment.

Repair uploads persist their current assignment ID. FF-08 resolution still
selects up to ten evidence IDs and stores durable assignment/evidence links.
Validation requires the correct request, uploader, purpose, creation time,
upload episode (when populated), and unused evidence. Old cycle uploads cannot
be selected for a new episode. Legacy V8 attachments with no episode remain
compatible with the existing time/uploader/link checks. Historical photos and
completed evidence are retained; no deletion endpoint is provided.

## Storage and limits

| Environment variable | Default / purpose |
| --- | --- |
| `PHOTO_STORAGE_PROVIDER` | `cloudinary`; explicit `local` for development/tests |
| `CLOUDINARY_CLOUD_NAME` | Required for Cloudinary operations |
| `CLOUDINARY_API_KEY`, `CLOUDINARY_API_SECRET` | Private environment values; never commit |
| `PHOTO_STORAGE_ROOT` | Required local root; choose a private directory outside the repo/web roots |
| `PHOTO_MAX_BYTES` | 5242880 bytes; allowed configuration 1–20971520 |
| `PHOTO_MAX_FILES` | 20 photos per request, including historical cycles; range 1–100 |
| `PHOTO_MAX_DIMENSION` | 6000 pixels per side; maximum configuration 10000 |
| `PHOTO_MAX_PIXELS` | 12000000 decoded pixels; maximum configuration 25000000 |
| `PHOTO_MULTIPART_MAX_FILE_SIZE` | `5MB` servlet file limit |
| `PHOTO_MULTIPART_MAX_REQUEST_SIZE` | `6MB` servlet multipart request limit |

One file is accepted per upload. Keep servlet limits aligned with application
byte limits when overriding them. Byte and normalized output sizes are both
checked. The total file-count check runs under the request row lock. No new
credential or real storage account is required in tests; they use temporary
directories and mocked Cloudinary transport. Missing provider configuration
causes a safe 503 on storage operations rather than an insecure public fallback.

Cloudinary uploads use signed HTTPS requests, `type=authenticated`, generated
public IDs and overwrite=false. Upload/deletion signatures and server-only
delivery URL signatures use SHA-256. Remote response IDs/types are verified.
Provider destinations are fixed HTTPS Cloudinary hosts, redirects are rejected,
timeouts are configured, and response reads are byte-bounded. No cloud secret
or signed URL is returned to the frontend. See Cloudinary's official
[authenticated assets](https://cloudinary.com/documentation/upload_parameters)
and [signature protocol](https://cloudinary.com/documentation/delivery_url_signatures).
Mocked request/response contract tests verify this implementation; a live
Cloudinary round trip requires the operator's account credentials and was not
part of automated verification.

Local storage uses flat UUID keys, create-new writes, no-follow file opens,
symlink/component/canonical-root checks and bounded reads. Original names are
never paths. Restrict storage-root filesystem permissions to the service;
untrusted local processes must not be able to replace directories during I/O.
Do not serve the root with a web/static file server. `.fixflow-photos/` is
ignored for optional development use; production roots should be external.
Provider switching does not automatically migrate old assets; records whose
provider does not match the configured backend fail safely with 503.

File operations are **not PostgreSQL transactions**. After successful storage,
a rollback synchronization removes the new object if metadata/commit fails.
Cleanup runs only for a confirmed rollback. An unknown database commit outcome
retains the object and logs a safe reconciliation warning, avoiding deletion
of content that might already have committed metadata.
Partial local write failure cleans up its newly created file; a collision never
overwrites/removes the existing file. Cleanup failure logs a content-free
warning. A process crash, indeterminate cloud timeout, or failure of deletion
can leave an orphan. For recovery, an operator must compare provider/local keys
against committed attachment storage references, allow a grace period longer
than in-flight uploads, and remove only confirmed unreferenced service-owned
keys. Back up both metadata and files, reconcile missing assets, and retry
failed cleanup. No automatic bulk deletion or unverified cleanup command is
exposed. These consistency limitations require operational reconciliation.

## Notification event and recipient matrix

All messages are fixed content-free envelopes. New rows carry a logical event
key and a per-recipient unique index prevents duplicate publication.

| Event | Relevant recipients, excluding actor |
| --- | --- |
| New request | Enabled/verified/non-suspended ADMINs |
| Changed reporter edit | ADMINs and relevant participants |
| Verification/rejection/status changes | Reporter, current technician if any, ADMINs |
| Official priority/URGENT change | Reporter, current technician, ADMINs |
| Assignment/reassignment | New/current technician, reporter, ADMINs |
| Acceptance/progress | Reporter, current technician, ADMINs |
| Resolution/reopening/confirmation/closure | Reporter, current technician if any, ADMINs |
| New shared comment | Reporter, current technician, ADMINs |

The actor is never notified about their own action. Unrelated reporters and
unassigned/previous technicians are not added as recipients. Historical receipts
are retained after reassignment; their requestId is null and requestAccessible
false when the recipient no longer has current access. No title, comment body,
admin note, repair note, filename or personal data is copied into messages.
Stored legacy title/message text is also withheld by the API. The frontend must
honor requestAccessible; following a remembered ID still gets normal authorization.

Notifications are written synchronously in the existing workflow transaction,
using the same datasource. Failure rolls back the comment/request/assignment/
history operation as applicable; there is no lossy after-commit callback. Status
history IDs, assignment/comment IDs or monotonic update timestamps form stable
event keys. No-op priorities, rejected/stale actions and rolled-back operations
do not publish. Repeated comment POSTs are distinct comments/events, not retries
with an idempotency key. Attachment uploads do not separately notify.

Recipient-only list/count/read/read-all remain available for safe historical
receipts even after request visibility changes. Read calls are idempotent and
read timestamps never precede creation. There are no public notification-create,
recipient-selection, WebSocket, push, extra email or external broker APIs.

## Concurrency, migration and verification

Comment/photo writes lock the request before checking mutable visibility and
assignment state, consistently with FF-08 reassignment. They do not change its
concurrency token. Notification reads do not affect request tokens. All queries
are database-filtered and paginated; notification request visibility is checked
in a bounded batch, not one query per receipt. Status/priority/assignment
notifications preserve existing expectedUpdatedAt and transaction semantics.

V9 adds nullable attachment.assignment_id and notification.event_key plus its
unique recipient/event index. Existing retrieval indexes already cover comment,
photo and recipient/unread queries. V1–V8 are unchanged. Tests initialize fresh
V9 databases and upgrade representative V8 comments/photos/evidence/notifications,
verifying preservation, the new FK and deduplication constraint. No developer
database is migrated, cleaned, baselined or reset for this task.

Run `.\mvnw.cmd -B clean verify` from backend with Docker. Tests use disposable
PostgreSQL and temporary storage, mock Cloudinary transport, and preserve the
144-test baseline. Coverage includes BOLA/role boundaries, private-comment
rejection, image validation, storage cleanup, evidence isolation, required
notification rollback, unread/read behavior and reassignment races. Frontend,
ML, analytics and deployment remain outside FF-09.
