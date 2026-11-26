# FF-04 authentication and authorization

FF-04 implements reporter registration, six-digit email verification, login,
JWT bearer authentication, current-user retrieval, and reusable ownership
authorization. It uses Java 21, the Spring Boot-managed Spring Security 7
libraries, Spring Mail, BCrypt, Spring Data JPA and PostgreSQL. V1–V7 are
unchanged; no new migration is needed. Hibernate still validates the schema.

## Flow and HTTP contracts

All bodies and responses use JSON. Validation and security failures use
`application/problem+json` with `type`, `title`, `status`, and `detail`.
Field validation may include an `errors` array. Never include credentials or
bearer tokens in URLs. Auth responses must not be cached.

| Endpoint | Request | Success |
| --- | --- | --- |
| `POST /api/auth/register` | `fullName`, `email`, `studentStaffId`, `department`, `password` | 202, generic message |
| `POST /api/auth/resend-otp` | `email` | 202, same generic message |
| `POST /api/auth/verify-email` | `email`, six-digit string `otp` | 200, verification message |
| `POST /api/auth/login` | `email`, `password` | 200, token response |
| `GET /api/auth/me` | `Authorization: Bearer <accessToken>` | 200, current user DTO |

Example registration (replace the reserved domain with an approved domain):

```json
{
  "fullName": "Example Reporter",
  "email": "reporter@university.example",
  "studentStaffId": "EXAMPLE-001",
  "department": "Engineering",
  "password": "replace-with-a-unique-password"
}
```

Names, email, student/staff ID and department are required and bounded to their
existing column lengths. Passwords require 12–72 characters and at most 72
UTF-8 bytes, respecting BCrypt's input limit. Email is stripped and lowercased
with `Locale.ROOT`; approved domains match exactly, without implicit subdomains.
Student/staff IDs are stripped and retain the schema's case-sensitive uniqueness.
Passwords are not stripped. Public fields such as `role`, `enabled` or
`emailVerified` never control account state or authority.

Registration always assigns REPORTER and persists disabled, unverified state.
Duplicate email or student/staff ID returns the same 202 body without creating
an account or issuing another email. Concurrent unique violations receive that
same result. Unknown accounts, verified accounts and premature resends receive
the same 202 response. The message is:
`If the account is eligible, a verification email will be sent.`
An existing pending account uses resend rather than registering again.

OTP expiry defaults to ten minutes, maximum attempts to five, and resend
cooldown to sixty seconds. OTPs use `SecureRandom`; only a nonce and a keyed
HMAC-SHA256 bound to the user, email and nonce are stored. The independent
server key protects six-digit codes against offline brute force after a
database-only leak. Every issue replaces the outstanding token and avoids
immediately repeating the prior code. User-row write locks serialize verify
and resend across application instances; the existing partial unique index
is a second safeguard. Wrong attempts and lockout persist even when HTTP 400
is returned. Consumed, invalidated, expired and exhausted codes cannot activate
accounts. Successful consumption and account activation commit together.

Email is sent synchronously within the issuing transaction with bounded SMTP
timeouts. A mail failure returns a safe 503 and rolls back registration or
replacement, allowing retry and retaining the old OTP on resend failure.
SMTP acceptance and database commit cannot be atomic: a rare commit failure
after delivery can produce an unusable email; resend recovers. There is no
durable email outbox or automatic delivery retry in FF-04.

Login uses Spring Security's `AuthenticationManager` and BCrypt encoder (cost
12). Unknown users, wrong passwords, disabled users and unverified users
receive the same 401 detail. Account status is checked after password hashing.
Example login response:

```json
{
  "accessToken": "<signed-JWT>",
  "tokenType": "Bearer",
  "expiresIn": 900,
  "user": {
    "id": 1,
    "fullName": "Example Reporter",
    "email": "reporter@university.example",
    "department": "Engineering",
    "role": "REPORTER"
  }
}
```

`me` returns just the `user` shape. No entity, password hash, OTP hash or SMTP
details appear in API responses. Invalid verification returns generic 400;
invalid fields/domain return 400; missing/invalid authentication returns 401;
insufficient permission returns 403; throttling returns 429. Infrastructure
and database errors remain sanitized by the global ProblemDetail advice.

## Token lifecycle and authorization

Tokens use HS256 with a Base64-encoded random signing key of at least 32 bytes.
Claims include numeric user-ID `sub`, `role`, `iss`, `aud`, `iat` and `exp`.
The resource server pins HS256 and validates issuer, audience, timestamps,
positive user ID, known role, and configured maximum lifetime. Expiration has
no clock-skew grace; keep servers time-synchronized. Default lifetime is 15
minutes, configurable up to one hour. Every authenticated request also checks
the current persisted enabled/verified flags and role. Disabling a user or
changing their role therefore rejects an existing token immediately.

No refresh tokens or logout endpoint are provided. Logout means deleting the
client's token; a copied token remains valid until expiration unless the user
is disabled or the signing key is rotated. Reauthenticate after expiry.
Keep access tokens in frontend memory, use HTTPS in deployment, and avoid
persistent browser storage where possible. Signing-key rotation invalidates
all tokens; OTP-key rotation requires users to request new verification codes.

Only the four explicit POST authentication endpoints are public. The category
GET and `me` accept REPORTER, TECHNICIAN or ADMIN; all other HTTP paths and
methods are denied by default. CORS permits only configured origins and
Authorization/Content-Type headers; credentials/cookies are disabled. The API
is stateless and accepts bearer tokens rather than session cookies, so CSRF,
form login, HTTP Basic and server-session logout are disabled.

Method security is enabled. Future request services must use
`@PreAuthorize("@requestAccess.canRead(authentication, #requestId)")` before
loading/returning private data. The guard allows an ADMIN to read an existing
request, its REPORTER owner, or a TECHNICIAN with a current assignment. Other
reporters, unassigned technicians, anonymous users and missing requests are
denied. Integration tests exercise this via a test-only secured service;
FF-05 request endpoints and transitions are deliberately not implemented.
New endpoints need an explicit filter-chain rule as well as their service's
role/ownership check. Do not broadly open `/api/**`.

There is no public ADMIN/TECHNICIAN provisioning route. Any future provisioning
service must require `hasRole('ADMIN')` and normalize identifiers consistently;
FF-04 does not add an initial-admin bootstrap or user-management feature.
Role and ownership checks are tested with secured test-only methods.

## Environment and local email

Supply these privately in the process environment. Spring Boot does not read
a `.env` file automatically. Never commit local secrets.

| Variable | Meaning / safe example |
| --- | --- |
| `DB_PASSWORD` | Existing database password; no fallback |
| `JWT_SIGNING_SECRET` | Independent Base64 random key, at least 32 decoded bytes; required |
| `OTP_HASHING_SECRET` | Different Base64 random key, at least 32 decoded bytes; required |
| `UNIVERSITY_EMAIL_DOMAINS` | Approved comma-separated domains; no assumed university, empty disables registration |
| `MAIL_FROM` | Authorized sender address; required |
| `SMTP_HOST` | SMTP server hostname; required |
| `SMTP_PORT` | Default `587`; local email catcher commonly `1025` |
| `SMTP_USERNAME`, `SMTP_PASSWORD` | Provider credentials, default empty |
| `SMTP_AUTH` | Default `true`; use `false` for a local catcher |
| `SMTP_STARTTLS` | Default `true` (required STARTTLS); `false` only for local testing |
| `CORS_ALLOWED_ORIGINS` | Exact comma-separated frontend origins, e.g. `http://localhost:5173`; empty allows no cross-origin frontend |
| `JWT_ISSUER`, `JWT_AUDIENCE` | Defaults `fixflow`, `fixflow-api` |
| `JWT_ACCESS_TOKEN_TTL` | Default `15m`, positive, at most `1h` |
| `OTP_TTL` | Default `10m`, positive, at most `30m` |
| `OTP_RESEND_COOLDOWN` | Default `60s`, positive, at most OTP lifetime |
| `OTP_MAX_ATTEMPTS` | Default `5`, range 1–10 |

Generate two keys separately using a cryptographically secure local secret
manager or `openssl rand -base64 32`. Do not paste real keys into chat or docs.
Startup rejects missing, short, malformed or identical keys. SMTP credentials
are never logged by application code; do not enable mail protocol debugging or
request-body/Authorization logging in deployment.

For a local email catcher, point `SMTP_HOST` to its hostname, set the mapped
SMTP port, disable SMTP_AUTH and SMTP_STARTTLS, and use a non-production sender
such as `fixflow@example.invalid`. View the code in the catcher's inbox UI.
Only configure university domains actually approved for the project. Empty
domain configuration intentionally rejects registration rather than guessing.
Review the database adoption instructions before running against an existing
database; tests never baseline or migrate the developer's database.

## Abuse protection, verification and limitations

Auth POSTs are limited to 30 per remote IP per fixed sixty-second
window, plus ten login attempts per normalized email per window. All login
attempts count, including successes. The bounded in-memory limiter permits at
most 10,000 active keys and fails closed at capacity. 429 IP responses include
`Retry-After: 60`. OTP attempts and resend cooldown are durable database rules.
Forwarded IP headers are not trusted. Configure a trusted reverse proxy and
edge limits before deployment, especially with multiple instances; in-memory
limits reset on restart and are not distributed. Shared NAT users can share
an IP quota. Production distributed/edge throttling and monitoring remain
deployment requirements. Generic responses and password-hash work reduce
enumeration; response timing and SMTP failures are not a formal constant-time
guarantee. No CAPTCHA, password reset or refresh-token infrastructure is added.

Run `.\mvnw.cmd -B clean verify` from `backend` with Java 21 and Docker.
The explicit test profile uses clearly test-only public key fixtures,
`university.example`, mocked SMTP, and disposable Testcontainers PostgreSQL.
Production secrets/email credentials are unnecessary in local tests and CI.
Tests cover registration, validation, duplicates, activation, OTP expiry,
attempt persistence, cooldown/reuse, concurrent verification/resends, email
failure rollback, login failures, JWT claim/signature/expiry rejection,
account disablement, missing required claims, default denial, role checks,
request ownership and CORS.
