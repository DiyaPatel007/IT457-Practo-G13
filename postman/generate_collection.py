"""Generates practo-lite.postman_collection.json. Edit this file (not the JSON) when the API changes:

    python postman/generate_collection.py
"""
import json
from pathlib import Path


def req(name, method, path, auth=None, body=None, tests=None, headers=None, query=None, prerequest=None):
    url = "{{baseUrl}}" + path
    h = [{"key": "Content-Type", "value": "application/json"}] if body is not None else []
    if headers:
        h += [{"key": k, "value": v} for k, v in headers.items()]
    url_obj = {"raw": url + (("?" + "&".join(f"{k}={v}" for k, v in query.items())) if query else ""),
               "host": ["{{baseUrl}}"], "path": path.strip("/").split("/")}
    if query:
        url_obj["query"] = [{"key": k, "value": v} for k, v in query.items()]
    item = {"name": name, "request": {"method": method, "header": h, "url": url_obj}}
    if auth:
        item["request"]["auth"] = {"type": "bearer",
                                   "bearer": [{"key": "token", "value": "{{" + auth + "}}", "type": "string"}]}
    else:
        item["request"]["auth"] = {"type": "noauth"}
    if body is not None:
        item["request"]["body"] = {"mode": "raw", "raw": json.dumps(body, indent=2)}
    events = []
    if tests:
        events.append({"listen": "test", "script": {"type": "text/javascript", "exec": tests}})
    if prerequest:
        events.append({"listen": "prerequest", "script": {"type": "text/javascript", "exec": prerequest}})
    if events:
        item["event"] = events
    return item


def status(code, extra=None):
    return [f"pm.test('status {code}', () => pm.response.to.have.status({code}));"] + (extra or [])


def login(name, email_var, token_var):
    """Cognito InitiateAuth (USER_PASSWORD_AUTH): login is not an API endpoint of ours."""
    body = {"AuthFlow": "USER_PASSWORD_AUTH", "ClientId": "{{clientId}}",
            "AuthParameters": {"USERNAME": "{{" + email_var + "}}", "PASSWORD": "{{password}}"}}
    return {"name": name, "request": {
        "method": "POST", "auth": {"type": "noauth"},
        "header": [{"key": "Content-Type", "value": "application/x-amz-json-1.1"},
                   {"key": "X-Amz-Target", "value": "AWSCognitoIdentityProviderService.InitiateAuth"}],
        "body": {"mode": "raw", "raw": json.dumps(body, indent=2)},
        "url": {"raw": "https://cognito-idp.{{region}}.amazonaws.com/", "protocol": "https",
                "host": ["cognito-idp", "{{region}}", "amazonaws", "com"], "path": [""]}},
        "event": [{"listen": "test", "script": {"type": "text/javascript", "exec": [
            "pm.test('login 200', () => pm.response.to.have.status(200));",
            f"pm.collectionVariables.set('{token_var}', pm.response.json().AuthenticationResult.AccessToken);"]}}]}


RUN_ID = ["if (!pm.collectionVariables.get('runId')) { pm.collectionVariables.set('runId', Date.now().toString()); }"]
SLOT_DATE = ["const d = new Date(Date.now() + 2 * 86400000);",
             "pm.collectionVariables.set('slotDate', d.toISOString().slice(0, 10));"]
EITHER_201_409 = ["pm.test('201, or 409 if already registered', () => pm.expect([201, 409]).to.include(pm.response.code));"]

folders = [
    {"name": "0 Setup: register and log in", "item": [
        req("Register patient (#1)", "POST", "/auth/register", tests=EITHER_201_409,
            body={"email": "{{patientEmail}}", "password": "{{password}}", "role": "PATIENT",
                  "name": "Postman Patient", "phone": "+919800000001"}),
        req("Register doctor (#1)", "POST", "/auth/register", tests=EITHER_201_409,
            body={"email": "{{doctorEmail}}", "password": "{{password}}", "role": "DOCTOR",
                  "name": "Dr Postman", "phone": "+919800000002"}),
        login("Login patient (Cognito)", "patientEmail", "patientToken"),
        login("Login doctor (Cognito)", "doctorEmail", "doctorToken"),
        login("Login admin (Cognito, created by seed.py)", "adminEmail", "adminToken"),
    ]},
    {"name": "1 Doctor workflow", "item": [
        req("Own user profile, saves doctorId (#2)", "GET", "/users/me", auth="doctorToken", tests=status(200, [
            "pm.collectionVariables.set('doctorId', pm.response.json().userId);",
            "pm.test('role DOCTOR', () => pm.expect(pm.response.json().role).to.eql('DOCTOR'));"])),
        req("Create or update doctor profile (#5)", "PUT", "/doctors/me", auth="doctorToken", tests=status(200),
            body={"speciality": "Dermatology", "city": "Gandhinagar", "fee": 600, "clinicName": "DAU Health Centre",
                  "experienceYears": 8, "bio": "Created from Postman"}),
        req("Create slots for one day (#8)", "POST", "/doctors/me/slots", auth="doctorToken", prerequest=SLOT_DATE,
            body={"date": "{{slotDate}}", "startTimes": ["10:00", "10:30", "11:00"]},
            tests=status(201, ["const b = pm.response.json(); const all = b.created.concat(b.skipped);",
                               "pm.collectionVariables.set('slotStart', all[0].slotStart);",
                               "pm.collectionVariables.set('slotStart2', all[1].slotStart);"])),
    ]},
    {"name": "2 Admin", "item": [
        req("Verify doctor (#4)", "PATCH", "/admin/doctors/{{doctorId}}/verify", auth="adminToken",
            body={"verified": True}, tests=status(200)),
    ]},
    {"name": "3 Patient workflow", "item": [
        req("Search doctors (#6)", "GET", "/doctors", query={"city": "Gandhinagar", "speciality": "Dermatology"},
            tests=status(200, ["pm.test('our doctor is listed', () => pm.expect(pm.response.json().items"
                               ".map(d => d.doctorId)).to.include(pm.collectionVariables.get('doctorId')));"])),
        req("Doctor details (#7)", "GET", "/doctors/{{doctorId}}", tests=status(200)),
        req("Available slots (#9)", "GET", "/doctors/{{doctorId}}/slots", tests=status(200)),
        req("Update own profile (#3)", "PATCH", "/users/me", auth="patientToken", body={"phone": "+919800000009"},
            tests=status(200)),
        req("Book a slot (#10)", "POST", "/appointments", auth="patientToken",
            body={"doctorId": "{{doctorId}}", "slotStart": "{{slotStart}}", "reason": "Skin rash"},
            tests=status(201, ["pm.collectionVariables.set('appointmentId', pm.response.json().appointmentId);",
                               "pm.test('BOOKED', () => pm.expect(pm.response.json().status).to.eql('BOOKED'));"])),
        req("Book the same slot again: 409 SLOT_TAKEN", "POST", "/appointments", auth="patientToken",
            body={"doctorId": "{{doctorId}}", "slotStart": "{{slotStart}}"},
            tests=status(409, ["pm.test('SLOT_TAKEN', () => pm.expect(pm.response.json().error).to.eql('SLOT_TAKEN'));"])),
        req("Book with Idempotency-Key", "POST", "/appointments", auth="patientToken", prerequest=RUN_ID,
            headers={"Idempotency-Key": "postman-{{runId}}"},
            body={"doctorId": "{{doctorId}}", "slotStart": "{{slotStart2}}"},
            tests=["pm.test('201 first time', () => pm.expect([200, 201]).to.include(pm.response.code));"]),
        req("Repeat the same Idempotency-Key: 200, same appointment", "POST", "/appointments", auth="patientToken",
            headers={"Idempotency-Key": "postman-{{runId}}"},
            body={"doctorId": "{{doctorId}}", "slotStart": "{{slotStart2}}"}, tests=status(200)),
        req("My appointments as patient (#11)", "GET", "/appointments", auth="patientToken", tests=status(200)),
        req("Received appointments as doctor (#11)", "GET", "/appointments", auth="doctorToken", tests=status(200)),
        req("Get one appointment (optional)", "GET", "/appointments/{{appointmentId}}", auth="patientToken",
            tests=status(200)),
        req("Cancel appointment (#12)", "POST", "/appointments/{{appointmentId}}/cancel", auth="patientToken",
            tests=status(200, ["pm.test('CANCELLED', () => pm.expect(pm.response.json().status).to.eql('CANCELLED'));"])),
        req("Re-book the freed slot: 201", "POST", "/appointments", auth="patientToken",
            body={"doctorId": "{{doctorId}}", "slotStart": "{{slotStart}}"},
            tests=status(201, ["pm.collectionVariables.set('appointmentId', pm.response.json().appointmentId);"])),
        req("Doctor marks COMPLETED (#13): 409 TOO_EARLY unless StrictTimeRules=false", "PATCH",
            "/appointments/{{appointmentId}}/status", auth="doctorToken", body={"status": "COMPLETED"},
            tests=["pm.test('200, or 409 TOO_EARLY with strict rules', () => "
                   "pm.expect([200, 409]).to.include(pm.response.code));"]),
    ]},
    {"name": "4 Negative and security tests", "item": [
        req("No token: 401", "GET", "/users/me", tests=status(401)),
        req("Patient on doctor route: 403", "PUT", "/doctors/me", auth="patientToken",
            body={"speciality": "ENT", "city": "Surat", "fee": 100}, tests=status(403)),
        req("Doctor tries to book: 403", "POST", "/appointments", auth="doctorToken",
            body={"doctorId": "{{doctorId}}", "slotStart": "{{slotStart}}"}, tests=status(403)),
        req("Patient on admin route: 403", "PATCH", "/admin/doctors/{{doctorId}}/verify", auth="patientToken",
            body={"verified": False}, tests=status(403)),
        req("Invalid body: 400 VALIDATION_FAILED", "POST", "/auth/register",
            body={"email": "bad", "password": "x", "role": "PATIENT"},
            tests=status(400, ["pm.test('VALIDATION_FAILED', () => "
                               "pm.expect(pm.response.json().error).to.eql('VALIDATION_FAILED'));"])),
        req("Self-register as ADMIN: 400", "POST", "/auth/register", prerequest=RUN_ID, tests=status(400),
            body={"email": "x-{{runId}}@practo-lite.test", "password": "{{password}}", "role": "ADMIN",
                  "name": "Mallory"}),
        req("Search without city: 400", "GET", "/doctors", tests=status(400)),
        req("Unknown doctor: 404", "GET", "/doctors/does-not-exist", tests=status(404)),
        req("Slot range over 7 days: 400", "GET", "/doctors/{{doctorId}}/slots",
            query={"from": "2030-01-01T00:00:00Z", "to": "2030-01-20T00:00:00Z"}, tests=status(400)),
    ]},
]

variables = [
    ("baseUrl", "https://REPLACE.execute-api.ap-south-1.amazonaws.com"), ("region", "ap-south-1"),
    ("clientId", "REPLACE_WITH_UserPoolClientId_OUTPUT"), ("password", "REPLACE_WITH_SEED_PASSWORD"),
    ("patientEmail", "postman.patient@practo-lite.test"), ("doctorEmail", "postman.doctor@practo-lite.test"),
    ("adminEmail", "admin@practo-lite.test"), ("patientToken", ""), ("doctorToken", ""), ("adminToken", ""),
    ("doctorId", ""), ("slotDate", ""), ("slotStart", ""), ("slotStart2", ""), ("appointmentId", ""), ("runId", ""),
]

collection = {
    "info": {
        "name": "Practo-Lite API (IT457 Group 13)",
        "description": "Run the folders in order with the Collection Runner. First set baseUrl, clientId and "
                       "password (stack outputs and scripts/.seed/seed-output.json). The admin user is created by "
                       "scripts/seed.py; there is no public way to become admin.",
        "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json",
    },
    "item": folders,
    "variable": [{"key": k, "value": v} for k, v in variables],
}

out = Path(__file__).with_name("practo-lite.postman_collection.json")
out.write_text(json.dumps(collection, indent=2), encoding="utf-8")
print(f"{sum(len(f['item']) for f in folders)} requests written to {out}")
