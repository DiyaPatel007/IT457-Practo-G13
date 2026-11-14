"""Seed a deployed stack with a fixed, repeatable data set, mostly THROUGH THE API itself.

Creates:
  * 1 admin       admin@practo-lite.test              (Cognito admin API: there is no public way to become admin)
  * 50 verified doctors  doctor01..doctor50@practo-lite.test  5 cities x 5 specialities, 10 slots each
  * 3 unverified doctors doctor51..doctor53@practo-lite.test  (must never appear in search)
  * 20 patients   patient01..patient20@practo-lite.test

The ".test" domain is reserved, so no real mailbox is ever involved. A random password is generated once and
stored in scripts/.seed/seed-output.json (git-ignored), together with the IDs the k6 scripts need.

Usage:
  python scripts/seed.py --stack practo-lite
"""
from __future__ import annotations

import argparse
import datetime as dt
import time

import boto3

from practo_common import (REGION, SEED_FILE, Api, generate_password, login, read_seed, stack_outputs,
                           write_json)

CITIES = ["Ahmedabad", "Gandhinagar", "Mumbai", "Bengaluru", "Delhi"]
# The five most consulted specialities in Practo's "Rise of Telemedicine 2020" data (GP, dermatology,
# gynaecology, then paediatrics and ENT).
SPECIALITIES = ["General Physician", "Dermatology", "Gynaecology", "Paediatrics", "ENT"]
FIRST = ["Asha", "Ravi", "Meera", "Arjun", "Kavya", "Rohan", "Isha", "Vikram", "Nisha", "Kiran"]
LAST = ["Shah", "Patel", "Mehta", "Rao", "Iyer"]
SLOT_TIMES = ["10:00", "10:30", "11:00", "11:30", "12:00", "15:00", "15:30", "16:00", "16:30", "17:00"]
IST = dt.timezone(dt.timedelta(hours=5, minutes=30))


def ensure_user(api: Api, client_id: str, email: str, password: str, role: str, name: str) -> tuple[str, str]:
    status, body, _ = api.call("POST", "/auth/register", body={
        "email": email, "password": password, "role": role, "name": name})
    if status not in (201, 409):
        raise RuntimeError(f"register {email}: {status} {body}")
    token = login(client_id, email, password)
    status, me, _ = api.call("GET", "/users/me", token)
    if status != 200:
        raise RuntimeError(f"/users/me {email}: {status} {me}")
    return me["userId"], token


def ensure_admin(pool_id: str, client_id: str, password: str) -> str:
    cognito = boto3.client("cognito-idp", region_name=REGION)
    email = "admin@practo-lite.test"
    try:
        cognito.admin_create_user(UserPoolId=pool_id, Username=email, MessageAction="SUPPRESS",
                                  UserAttributes=[{"Name": "email", "Value": email},
                                                  {"Name": "email_verified", "Value": "true"}])
    except cognito.exceptions.UsernameExistsException:
        pass
    cognito.admin_set_user_password(UserPoolId=pool_id, Username=email, Password=password, Permanent=True)
    cognito.admin_add_user_to_group(UserPoolId=pool_id, Username=email, GroupName="Admin")
    return login(client_id, email, password)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--stack", default="practo-lite")
    parser.add_argument("--doctors", type=int, default=50)
    parser.add_argument("--patients", type=int, default=20)
    parser.add_argument("--slot-days", type=int, default=2, help="days ahead (from tomorrow) to open slots")
    args = parser.parse_args()

    out = stack_outputs(args.stack)
    api = Api(out["ApiUrl"])
    client_id, pool_id = out["UserPoolClientId"], out["UserPoolId"]
    password = read_seed()["password"] if SEED_FILE.exists() else generate_password()

    print(f"Seeding {args.stack} at {out['ApiUrl']}")
    admin_token = ensure_admin(pool_id, client_id, password)

    tomorrow = dt.datetime.now(IST).date() + dt.timedelta(days=1)
    dates = [(tomorrow + dt.timedelta(days=d)).isoformat() for d in range(args.slot_days)]
    doctors, unverified = [], []
    total = args.doctors + 3
    for i in range(1, total + 1):
        email = f"doctor{i:02d}@practo-lite.test"
        name = f"Dr {FIRST[i % len(FIRST)]} {LAST[(i // len(FIRST)) % len(LAST)]} {i:02d}"
        city = CITIES[(i - 1) % len(CITIES)]
        speciality = SPECIALITIES[((i - 1) // len(CITIES)) % len(SPECIALITIES)]
        doctor_id, token = ensure_user(api, client_id, email, password, "DOCTOR", name)
        status, body, _ = api.call("PUT", "/doctors/me", token, {
            "speciality": speciality, "city": city, "fee": 300 + 50 * (i % 8),
            "experienceYears": 3 + i % 20, "clinicName": f"{city} Care Clinic"})
        if status != 200:
            raise RuntimeError(f"profile {email}: {status} {body}")
        verified = i <= args.doctors
        status, body, _ = api.call("PATCH", f"/admin/doctors/{doctor_id}/verify", admin_token,
                                   {"verified": verified})
        if status != 200:
            raise RuntimeError(f"verify {email}: {status} {body}")
        slots = []
        for day in dates:
            status, body, _ = api.call("POST", "/doctors/me/slots", token, {"date": day, "startTimes": SLOT_TIMES})
            if status != 201:
                raise RuntimeError(f"slots {email}: {status} {body}")
            slots += [s["slotStart"] for s in body["created"]]
            slots += [s["slotStart"] for s in body["skipped"] if s["reason"] == "DUPLICATE"]
        record = {"doctorId": doctor_id, "email": email, "name": name, "city": city,
                  "speciality": speciality, "slots": sorted(set(slots))}
        (doctors if verified else unverified).append(record)
        print(f"  doctor {i:02d}/{total} {'verified' if verified else 'UNVERIFIED'}: {len(record['slots'])} slots")
        time.sleep(0.05)

    patients = []
    for i in range(1, args.patients + 1):
        email = f"patient{i:02d}@practo-lite.test"
        user_id, _ = ensure_user(api, client_id, email, password, "PATIENT", f"Patient {i:02d}")
        patients.append({"userId": user_id, "email": email})
    print(f"  {len(patients)} patients")

    write_json(SEED_FILE, {
        "stack": args.stack, "apiUrl": out["ApiUrl"], "clientId": client_id, "userPoolId": pool_id,
        "region": REGION, "password": password, "adminEmail": "admin@practo-lite.test",
        "doctors": doctors, "unverifiedDoctors": unverified, "patients": patients,
        "cities": CITIES, "specialities": SPECIALITIES, "seededAt": dt.datetime.now(dt.timezone.utc).isoformat(),
    })
    print(f"Done. Seed details (including the generated password) saved to {SEED_FILE}")


if __name__ == "__main__":
    main()
