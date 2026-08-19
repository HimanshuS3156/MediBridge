# MedTour India — Medical Tourism Platform

International-patient platform: discover hospitals, compare treatments, book consultations —
now with accounts, a Doctor Dashboard, transactional email, and an admin triage queue.

- **Frontend:** HTML + CSS + vanilla JavaScript (unchanged — same files as the Spring Boot pass)
- **Backend:** plain Java (`com.sun.net.httpserver`, built into the JDK) — **no Spring, no Maven**
- **Database:** MySQL

> **Migration note:** this is the full feature set of the Spring Boot version, ported back onto
> the original zero-dependency `com.sun.net.httpserver` foundation. Spring Data JPA became hand-written
> JDBC; Spring Security + jjwt became a small hand-rolled JWT (HMAC-SHA256, `javax.crypto`); BCrypt
> became `PBKDF2WithHmacSHA256` (also `javax.crypto`, already in the JDK — see §3 below for why);
> Bean Validation became a small `ValidationErrors` collector that reports every bad field at once,
> same as before. Resend email still goes out over the JDK's own `java.net.http.HttpClient`. The
> **only** external jar you need is still the MySQL JDBC driver.

---

## 1. Folder structure

```
medtour/
├── frontend/                    → unchanged from the Spring Boot version
│   ├── index.html, providers.html, treatments.html, appointment.html
│   ├── admin.html, login.html, register.html, verify-email.html, check-status.html
│   ├── doctor-dashboard.html
│   ├── css/style.css
│   └── js/  (api.js, countries.js, doctor-dashboard.js, feedback-widget.js, form-widgets.js)
├── backend/
│   └── src/medtour/
│       ├── Main.java                  → starts the server, wires up every route
│       ├── Database.java              → MySQL connection (env vars or edit directly)
│       ├── Config.java                → booking window, fees, JWT/mail settings (env-overridable)
│       ├── Http.java                  → CORS, JSON responses, body/query parsing, path segments
│       ├── Json.java                  → hand-written JSON writer (nested maps/lists) + flat-body parser
│       ├── ApiException.java          → uniform { status, message, fieldErrors } errors
│       ├── ValidationErrors.java      → collects every field error on a form before rejecting it
│       ├── JwtUtil.java               → HS256 JWT issue/verify (javax.crypto, no library)
│       ├── PasswordUtil.java          → PBKDF2WithHmacSHA256 password hashing (javax.crypto)
│       ├── AuthContext.java           → reads "Authorization: Bearer ...", resolves + authorizes the caller
│       ├── RateLimiter.java           → in-memory login brute-force guard
│       ├── TextSanitizer.java         → strips HTML tags from freeform text before it's stored
│       ├── EmailService.java          → async Resend transactional email (java.net.http)
│       ├── EmailTemplates.java        → branded HTML email bodies
│       ├── HospitalHandler.java       → GET /api/hospitals
│       ├── DoctorHandler.java         → GET /api/doctors, /api/doctors/specializations
│       ├── TreatmentHandler.java      → GET /api/treatments, /api/treatments/{id}
│       ├── AppointmentHandler.java    → booking, estimate, lookup, admin list/status
│       ├── AuthHandler.java           → register, login, verify-email
│       ├── FeedbackHandler.java       → public submit, admin list
│       ├── ConfigHandler.java         → GET /api/config
│       ├── DoctorDashboardHandler.java→ everything under /api/doctor/** (profile, appointments,
│       │                                 patients, notifications, availability, settings)
│       └── StaticFileHandler.java     → serves the frontend files
└── database/
    └── schema.sql              → creates every table + sample data + seeded admin login
```

No subpackages — every class lives directly in `backend/src/medtour/`, so the whole thing still
compiles with one `javac` invocation and no build tool.

---

## 2. Setup — step by step

### Step A: Install MySQL
Install MySQL Server (if not already installed) and make sure it's running.

### Step B: Create the database
```bash
mysql -u root -p < database/schema.sql
```
This creates the `medtour` database, all 8 tables, sample hospitals/doctors/treatments, and a
seeded admin login (see §5).

### Step C: Set your MySQL credentials
Either set environment variables before running the server —
```bash
export MEDTOUR_DB_USER=root
export MEDTOUR_DB_PASSWORD=yourpassword
```
— or edit the defaults directly in `backend/src/medtour/Database.java`.

### Step D: Download the MySQL JDBC driver
The backend needs one external jar (this is the JDBC driver, not a framework).
Download **mysql-connector-j** from `https://dev.mysql.com/downloads/connector/j/` and place it at
`backend/lib/mysql-connector-j-9.x.x.jar`.

### Step E: Compile the backend
```bash
cd backend
mkdir -p out
javac -d out src/medtour/*.java
```

### Step F: Run the server
```bash
java -cp "out:lib/mysql-connector-j-9.x.x.jar" medtour.Main
```
(On Windows, use a semicolon instead of a colon: `-cp "out;lib\mysql-connector-j-9.x.x.jar"`)

You should see:
```
MedTour India server running:  http://localhost:8080
MySQL connection:               OK
```

### Step G: Open the site
Go to **http://localhost:8080**. One server serves both the website and the API.

### Optional environment variables

| Variable | Purpose | Default |
|---|---|---|
| `MEDTOUR_DB_URL` / `MEDTOUR_DB_USER` / `MEDTOUR_DB_PASSWORD` | MySQL connection | `jdbc:mysql://localhost:3306/medtour...` / `root` / `root` |
| `MEDTOUR_JWT_SECRET` | Base64, 256-bit+. **Change before deploying anywhere real.** Generate with `openssl rand -base64 32` | a dev-only placeholder baked into `JwtUtil.java` |
| `MEDTOUR_JWT_EXPIRY` | Token lifetime, ms | `86400000` (24h) |
| `MEDTOUR_BOOKING_WINDOW_DAYS` | How far ahead a patient can book | `180` |
| `MEDTOUR_FEE_AIRPORT_PICKUP` / `MEDTOUR_FEE_TRAVEL_ASSISTANCE` | Flat INR service fees | `1500` / `3500` |
| `MEDTOUR_FRONTEND_URL` | Base URL used inside emails (the "Verify Email" link) | `http://localhost:8080` |
| `MEDTOUR_VERIFY_TOKEN_HOURS` | Email verification link lifetime | `24` |
| `RESEND_API_KEY` / `RESEND_FROM_EMAIL` | Transactional email — see §8 | unset = email sending disabled, logged only |
| `MEDTOUR_ADMIN_NOTIFY_EMAIL` | Where "new doctor registered" notices go | `admin@medtour.in` |
| `PORT` | HTTP port | `8080` |

---

## 3. Why PBKDF2 instead of BCrypt

The Spring Boot version used `BCryptPasswordEncoder`, which comes from the
`spring-security-crypto` jar. Bringing in a real BCrypt implementation here would mean either a
second external jar (breaking the "one dependency: the JDBC driver" rule this backend format is
built around) or hand-transcribing Blowfish's P-array/S-box constant tables by hand, which is a lot
of code to trust without a library's test suite behind it.

`PBKDF2WithHmacSHA256` is a NIST-recommended password-hashing algorithm and — unlike BCrypt — is
already built into the JDK (`javax.crypto`), so it needs zero extra jars. `PasswordUtil.java` uses
it with a random 16-byte salt and 100,000 iterations, stored as
`pbkdf2_sha256$<iterations>$<saltB64>$<hashB64>`. The seeded admin account in `schema.sql` is
already hashed this way.

## 4. Auth

- `POST /api/auth/register` — `{ fullName, email, password, phone, country, role: "PATIENT"|"DOCTOR", ...doctor fields if role=DOCTOR }`
- `POST /api/auth/login` — `{ email, password }` → `{ token, fullName, email, role, emailVerified }`
- Send the token back as `Authorization: Bearer <token>`. `js/api.js` does this automatically once
  `saveSession()` has stored a token in `localStorage`.
- Roles: `PATIENT`, `DOCTOR` (self-registerable), `ADMIN` (seeded only — see `schema.sql`, login
  `admin@medtour.in` / `ChangeMe123!`, **change this password immediately**).
- `GET/PUT /api/appointments*` and `GET /api/feedback` require the `ADMIN` role, `/api/doctor/**`
  requires `DOCTOR` — enforced at the top of every handler via `AuthContext.requireRole()`, not
  just hidden in the UI. A patient token hitting those endpoints gets a real 401/403.
- Passwords are hashed with PBKDF2 (§3), never stored or logged in plain text. Duplicate email is
  rejected with 409 before hashing even happens.
- A brute-force guard (`RateLimiter.java`) blocks an email after 8 failed login attempts for 15
  minutes — a simple in-memory speed bump, not a distributed defense (see the file's own comments).

## 5. Error-handling contract

Every response is structured JSON — the frontend never sees a raw exception or stack trace, and
the status code is always accurate for what happened. Every handler's `catch` block funnels into
`Http.sendApiError()`, which turns any `ApiException` (or, as a last resort, any other exception)
into the same `{ status, message, fieldErrors }` shape:

| Situation | Status | Where |
|---|---|---|
| Success | 200 / 201 / 204 | Normal handler return |
| A form has one or more bad fields | 400 + `fieldErrors` map | `ValidationErrors.throwIfInvalid()` |
| Malformed JSON / missing param | 400 | Individual handler checks |
| Business-rule failure (e.g. "treatment not found") | whatever `ApiException` was thrown with | Service-style static methods |
| Wrong email/password | 401 | `AuthHandler.login()` |
| Missing/expired/invalid JWT | 401 | `AuthContext.requireUser()` |
| Valid token, wrong role | 403 | `AuthContext.requireRole()` |
| Unmapped HTTP method | 405 | Each handler's method check |
| Anything unanticipated | 500 + generic message (real exception logged server-side) | `Http.sendApiError()` |

`js/api.js`'s `handleResponse()` is the single place that turns a non-2xx response into a JS
`Error`; every page shows `err.message` through an inline alert or toast — never a raw stack trace.

## 6. Estimated cost (INR)

- `GET /api/appointments/estimate?doctorId=&airportPickup=&travelAssistance=` — public, read-only
  live preview while the patient is still filling out the form. Nothing is persisted.
- `POST /api/appointments` returns the same shape once the booking is actually saved.
- Both share one calculation (`AppointmentHandler.computePricing`) so the preview and the real
  charge can never drift apart: `consultationFeeInr` (from the doctor's row) + `airportPickupFeeInr`
  + `travelAssistanceFeeInr` (both configurable via env vars) = `estimatedTotalInr`.
- The client can send `airportPickup`/`travelAssistance` as booleans; it can never send a price —
  the backend always recomputes it from SQL and config.
- `GET /api/config` exposes `maxBookingDaysAhead` so the frontend date picker's max date is read
  from the same value the backend enforces.

## 7. Feedback

- `POST /api/feedback` — public, `{ name, email, rating: 1-5, comment, appointmentId? }`. Validated
  and HTML-stripped before it touches SQL.
- `GET /api/feedback` — admin only.
- Frontend: the star-rating widget (`js/feedback-widget.js`) in the footer of the main pages.

## 8. Transactional email (Resend)

All outbound email goes through **[Resend](https://resend.com)**'s REST API, called only from
`EmailService.java` via the JDK's `java.net.http.HttpClient` — no new library, and the frontend
never talks to Resend or sees the API key.

1. Create a Resend account and an API key at resend.com/api-keys.
2. `export RESEND_API_KEY=re_your_key_here` and optionally `export RESEND_FROM_EMAIL="MedTour India <no-reply@yourdomain.com>"` (must be a verified sender/domain — or use Resend's own `onboarding@resend.dev` for local testing, the default).
3. If `RESEND_API_KEY` is unset, sending is **disabled, not broken** — every send is logged and
   skipped, so registration/booking still work end-to-end without a Resend account.

Emails sent: registration welcome + verify link (patient/doctor), new-doctor admin notice,
appointment confirmation, appointment status change, appointment reschedule. Every send runs on a
small dedicated thread pool (`EmailService`'s executor) and never throws — a Resend outage never
fails the booking/registration it was triggered by.

## 9. Doctor Dashboard

A protected `doctor-dashboard.html`, backed by `/api/doctor/**` (every route requires a `DOCTOR`
token — enforced in `DoctorDashboardHandler.handle()`, not just hidden in the UI).

**Sections:** Dashboard (today's/upcoming appointments, total patients, pending requests), My
Profile, Appointments (filterable by status, with Accept/Reject/Complete/Reschedule/View),
Patients (aggregated from this doctor's own appointment history only), Availability (weekly
day/time slots), Consultation History, Notifications, Settings (change password).

**Privacy boundary:** every method resolves the `Doctor` row from the authenticated JWT
(`resolveCurrentDoctor()`) — a doctor id is never accepted from the client. Appointment
reads/writes always filter by `doctor_id` in SQL, so a doctor can only ever see or act on
appointments that are actually theirs.

**Status transitions a doctor can make themselves** (admins, via `admin.html`, aren't bound by this):
```
Pending   -> Confirmed | Rejected
Confirmed -> Completed | Rejected
Rejected, Completed -> (terminal)
```
Rescheduling is allowed from `Pending`/`Confirmed`, blocked once `Completed`/`Rejected`.

## 10. Email verification

`POST /api/auth/register` generates a verification token, emails a "Verify Email Address" link via
Resend, and `GET /api/auth/verify-email?token=...` marks the account verified (public, single-use,
expires after `MEDTOUR_VERIFY_TOKEN_HOURS`, default 24h).

**Verification is never required to log in** — deliberately, so a deployment without
`RESEND_API_KEY` configured can't lock people out of accounts they just created. Only the SHA-256
hash of the token is ever stored — a database leak alone can't be used to verify someone else's
account.

## 11. Check my appointment status

- `GET /api/appointments/lookup?referenceId=42&email=jane@example.com` — public. Returns the same
  404 whether the ID doesn't exist or the email doesn't match it, so the endpoint can't be used to
  enumerate valid reference IDs.
- Frontend: `check-status.html`.

## 12. Specialist search & filtering

- `GET /api/doctors?specialization=Cardiac%20Surgery` — exact match
- `GET /api/doctors?q=cardiac` — free-text, matches name or specialization
- `GET /api/doctors/specializations` — distinct values, for the filter dropdown
- All optional and combinable with the existing `?hospitalId=`.

## 13. Full API reference

| Method | Endpoint | Auth | Purpose |
|---|---|---|---|
| GET | `/api/hospitals?city=&q=` | public | List/search hospitals |
| GET | `/api/doctors?hospitalId=&specialization=&q=` | public | List/search doctors |
| GET | `/api/doctors/specializations` | public | Distinct specializations |
| GET | `/api/treatments?category=&q=` | public | List/search treatments |
| GET | `/api/treatments/{id}` | public | One treatment |
| GET | `/api/appointments/estimate?doctorId=&airportPickup=&travelAssistance=` | public | Live pricing preview |
| GET | `/api/appointments/lookup?referenceId=&email=` | public | Check appointment status |
| POST | `/api/appointments` | public/patient | Book a consultation |
| GET | `/api/appointments` | ADMIN | List every request |
| PUT | `/api/appointments/{id}` | ADMIN | `{"status":"Confirmed"}` |
| POST | `/api/auth/register` | public | Create a PATIENT or DOCTOR account |
| POST | `/api/auth/login` | public | Get a JWT |
| GET | `/api/auth/verify-email?token=` | public | Confirm an email address |
| POST | `/api/feedback` | public | Submit star rating + comment |
| GET | `/api/feedback` | ADMIN | List all feedback |
| GET | `/api/config` | public | `{ maxBookingDaysAhead }` |
| GET | `/api/doctor/dashboard` | DOCTOR | Stat cards |
| GET/PUT | `/api/doctor/profile` | DOCTOR | Own profile |
| GET | `/api/doctor/appointments?status=` | DOCTOR | Own appointments |
| GET | `/api/doctor/appointments/{id}` | DOCTOR | One (own) appointment |
| PUT | `/api/doctor/appointments/{id}/status` | DOCTOR | Accept/Reject/Complete |
| PUT | `/api/doctor/appointments/{id}/reschedule` | DOCTOR | `{"preferredDate":"..."}` |
| GET | `/api/doctor/consultation-history` | DOCTOR | Completed appointments |
| GET | `/api/doctor/patients` | DOCTOR | Own patients, aggregated |
| GET | `/api/doctor/notifications` | DOCTOR | In-app alerts |
| PUT | `/api/doctor/notifications/{id}/read` | DOCTOR | Mark one read |
| PUT | `/api/doctor/notifications/read-all` | DOCTOR | Mark all read |
| GET/POST | `/api/doctor/availability` | DOCTOR | Weekly slots |
| PUT/DELETE | `/api/doctor/availability/{id}` | DOCTOR | Edit/remove a slot |
| PUT | `/api/doctor/settings/password` | DOCTOR | Change password |
| GET | `/api/health` | public | Health check |

---

## 14. Troubleshooting

- **"MySQL connection: FAILED"** on startup → check your DB env vars / `Database.java` and that
  MySQL is actually running.
- **Blank cards / "Could not load hospitals"** → the Java server isn't running.
- **`ClassNotFoundException: com.mysql.cj.jdbc.Driver`** → the driver jar isn't on your classpath —
  double-check the `-cp` path in Step F.
- **401 right after logging in** → check `MEDTOUR_JWT_SECRET` is the same across restarts if you've
  set it explicitly; changing it invalidates every existing token.
- **Emails not arriving** → check `RESEND_API_KEY` is set; without it, sending is intentionally
  disabled and logged only (see §8) — everything else still works.
