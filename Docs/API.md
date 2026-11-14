# API reference

Base URL: the `ApiUrl` stack output, e.g. `https://abc123.execute-api.ap-south-1.amazonaws.com` (no stage path).
Authentication: `Authorization: Bearer <Cognito access token>` on every route not marked *public*.
Get a token with Cognito `InitiateAuth` (`USER_PASSWORD_AUTH`); see the Postman folder "0 Setup".

Times: `slotStart` values are UTC ISO-8601 (`2026-11-20T04:30:00Z`). Responses also include
`slotStartLocal` in IST (`2026-11-20T10:00:00+05:30`). Slots are 30 minutes long on a :00/:30 grid.

## Error format

```json
{"error": "SLOT_TAKEN", "message": "This slot has already been booked"}
{"error": "VALIDATION_FAILED", "message": "Request body is invalid", "details": ["fee: must be greater than or equal to 1"]}
```

| HTTP | Codes |
|---|---|
| 400 | VALIDATION_FAILED, MALFORMED_JSON, INVALID_PARAMETER, INVALID_TIME, INVALID_DATE, OFF_GRID, INVALID_RANGE, RANGE_TOO_LARGE, CITY_REQUIRED, NOTHING_TO_UPDATE, ROLE_NOT_ALLOWED, WEAK_PASSWORD, SLOT_IN_PAST, SELF_BOOKING, INVALID_STATUS, INVALID_IDEMPOTENCY_KEY, INVALID_PAGE_TOKEN |
| 401 | UNAUTHORIZED (from Spring) or API Gateway's own `{"message":"Unauthorized"}` (token missing/invalid on a protected route) |
| 403 | FORBIDDEN (wrong role), NOT_YOUR_APPOINTMENT |
| 404 | USER_NOT_FOUND, DOCTOR_NOT_FOUND, DOCTOR_ACCOUNT_NOT_FOUND, SLOT_NOT_FOUND, APPOINTMENT_NOT_FOUND, ROUTE_NOT_FOUND |
| 409 | EMAIL_EXISTS, SLOT_TAKEN, CONFLICT_RETRY, DUPLICATE_APPOINTMENT, NOT_CANCELLABLE, TOO_LATE_TO_CANCEL, INVALID_TRANSITION, TOO_EARLY, PROFILE_INCOMPLETE, CONCURRENT_UPDATE, SLOT_BOOKED, SLOT_STATE_MISMATCH |
| 503 | CAPACITY (DynamoDB throttling: a capacity limit, not a booking conflict) |
| 429 | API Gateway throttle (`ApiThrottleRate`/`ApiThrottleBurst`) |

---

## Users service

### 1. `POST /auth/register` (public)
```json
{"email": "ravi@practo-lite.test", "password": "Passw0rd1", "role": "PATIENT", "name": "Ravi Patel", "phone": "+919800000000"}
```
`role` is `PATIENT` or `DOCTOR` (`ADMIN` → 400). Password: 8+ characters with upper case, lower case and a digit.
**201** `{"userId": "<cognito sub>", "email": "...", "role": "PATIENT"}` · 400 · 409 EMAIL_EXISTS.
Doctors also get an unverified Doctors item.

### 2. `GET /users/me` (Patient, Doctor)
**200** `{"userId","email","name","phone","role","createdAt","updatedAt"}`

### 3. `PATCH /users/me` (Patient, Doctor)
`{"name": "New Name"}` and/or `{"phone": "+91..."}` → **200** profile · 400 NOTHING_TO_UPDATE.

### 4. `PATCH /admin/doctors/{doctorId}/verify` (Admin)
`{"verified": true}` → **200** `{"doctorId","verified"}` · 404 · 409 PROFILE_INCOMPLETE (city/speciality missing).
Verifying writes the search-index keys; unverifying removes them.

## Doctors service

### 5. `PUT /doctors/me` (Doctor)
```json
{"name": "Dr Ravi Shah", "speciality": "Cardiology", "city": "Ahmedabad", "fee": 700,
 "clinicName": "Shah Heart Clinic", "experienceYears": 12, "bio": "..."}
```
`name` optional (defaults to the registered name); `speciality`, `city`, `fee` required.
**200** `{"doctorId","name","speciality","city","fee","clinicName","experienceYears","bio","verified"}`.

### 6. `GET /doctors?city=Ahmedabad&speciality=Cardiology&name=shah&limit=10&nextToken=...` (public)
Verified doctors only. **200** `{"items":[{"doctorId","name","speciality","city","fee","clinicName","experienceYears"}], "nextToken": "..."}`.
A page can hold fewer than `limit` items while `nextToken` is present (DynamoDB filters after reading).

### 7. `GET /doctors/{doctorId}` (public)
**200** profile · 404 if missing or unverified.

### 8. `POST /doctors/me/slots` (Doctor)
```json
{"date": "2026-11-20", "startTimes": ["10:00", "10:30", "11:00"]}
```
Times in IST, max 48 per request, up to 30 days ahead.
**201** `{"created":[{"slotStart","slotStartLocal","status":"AVAILABLE","durationMin":30}], "skipped":[{"startTime","slotStart","reason":"PAST|TOO_FAR_AHEAD|DUPLICATE"}]}`

### 9. `GET /doctors/{doctorId}/slots?from=...&to=...` (public)
Default: now to now+7 days; at most 7 days. Only AVAILABLE future slots.
**200** `{"doctorId","from","to","items":[{"slotStart","slotStartLocal","status","durationMin"}]}`

### opt. `DELETE /doctors/me/slots/{slotStart}` (Doctor)
**204** · 404 SLOT_NOT_FOUND · 409 SLOT_BOOKED.

## Appointments service

### 10. `POST /appointments` (Patient)
```json
{"doctorId": "<id>", "slotStart": "2026-11-20T04:30:00Z", "reason": "Fever"}
```
Optional header `Idempotency-Key: <8-64 chars [A-Za-z0-9_-]>`.
**201** new booking · **200** retry with the same key returned the earlier booking ·
404 DOCTOR_NOT_FOUND / SLOT_NOT_FOUND · 409 SLOT_TAKEN / CONFLICT_RETRY · 503 CAPACITY.
```json
{"appointmentId","patientId","patientName","doctorId","doctorName","slotStart","slotStartLocal",
 "status":"BOOKED","reason","createdAt","updatedAt"}
```

### 11. `GET /appointments?from=&to=&status=&limit=&nextToken=` (Patient, Doctor)
A patient sees appointments they booked; a doctor sees appointments they received.
Default range: 30 days back to 60 days ahead (max 180 days). **200** `{"items":[...], "nextToken"}`.

### opt. `GET /appointments/{appointmentId}` (its patient or doctor)
**200** · 403 NOT_YOUR_APPOINTMENT · 404.

### 12. `POST /appointments/{appointmentId}/cancel` (its patient or doctor)
No body. **200** appointment with `status: CANCELLED`, `cancelledBy: PATIENT|DOCTOR`; the slot is AVAILABLE again.
409 NOT_CANCELLABLE / TOO_LATE_TO_CANCEL · 403 · 404.

### 13. `PATCH /appointments/{appointmentId}/status` (its doctor)
`{"status": "COMPLETED"}` or `{"status": "NO_SHOW"}` → **200** · 400 INVALID_STATUS · 409 INVALID_TRANSITION /
TOO_EARLY (before slot start, unless the stack has `StrictTimeRules=false`) · 403.
