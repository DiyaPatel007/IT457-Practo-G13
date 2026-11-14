"""Reset booking state before every experiment run, so runs are repeatable.

Default: delete ALL appointments and set every slot back to AVAILABLE.
--reslot: also delete all slots and open fresh ones from tomorrow (use when the seeded slots are in the past),
          then update scripts/.seed/seed-output.json with the new slot times.

Usage:
  python scripts/reset.py --stack practo-lite
  python scripts/reset.py --stack practo-lite --reslot --days 2
"""
from __future__ import annotations

import argparse
import datetime as dt

import boto3

from practo_common import REGION, SEED_FILE, read_seed, stack_outputs, write_json

SLOT_TIMES = ["10:00", "10:30", "11:00", "11:30", "12:00", "15:00", "15:30", "16:00", "16:30", "17:00"]
IST = dt.timezone(dt.timedelta(hours=5, minutes=30))


def scan_all(table, **kwargs):
    res = table.scan(**kwargs)
    yield from res["Items"]
    while "LastEvaluatedKey" in res:
        res = table.scan(ExclusiveStartKey=res["LastEvaluatedKey"], **kwargs)
        yield from res["Items"]


def utc_key(day: str, hhmm: str) -> str:
    local = dt.datetime.fromisoformat(f"{day}T{hhmm}:00").replace(tzinfo=IST)
    return local.astimezone(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--stack", default="practo-lite")
    parser.add_argument("--reslot", action="store_true")
    parser.add_argument("--days", type=int, default=2)
    args = parser.parse_args()

    out = stack_outputs(args.stack)
    db = boto3.resource("dynamodb", region_name=REGION)
    appointments = db.Table(out["AppointmentsTableName"])
    slots = db.Table(out["SlotsTableName"])

    deleted = 0
    with appointments.batch_writer() as batch:
        for item in scan_all(appointments, ProjectionExpression="appointmentId"):
            batch.delete_item(Key={"appointmentId": item["appointmentId"]})
            deleted += 1
    print(f"Deleted {deleted} appointments")

    if args.reslot:
        seed = read_seed()
        with slots.batch_writer() as batch:
            for item in scan_all(slots, ProjectionExpression="doctorId, slotStart"):
                batch.delete_item(Key={"doctorId": item["doctorId"], "slotStart": item["slotStart"]})
        tomorrow = dt.datetime.now(IST).date() + dt.timedelta(days=1)
        days = [(tomorrow + dt.timedelta(days=d)).isoformat() for d in range(args.days)]
        now = dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        with slots.batch_writer() as batch:
            for doctor in seed["doctors"] + seed["unverifiedDoctors"]:
                keys = [utc_key(d, t) for d in days for t in SLOT_TIMES]
                for key in keys:
                    batch.put_item(Item={"doctorId": doctor["doctorId"], "slotStart": key, "status": "AVAILABLE",
                                         "durationMin": 30, "createdAt": now})
                doctor["slots"] = keys
        write_json(SEED_FILE, seed)
        print(f"Recreated slots for {len(seed['doctors']) + len(seed['unverifiedDoctors'])} doctors on {days}")
    else:
        freed = 0
        for item in scan_all(slots, FilterExpression="#s = :b", ExpressionAttributeNames={"#s": "status"},
                             ExpressionAttributeValues={":b": "BOOKED"}):
            slots.update_item(Key={"doctorId": item["doctorId"], "slotStart": item["slotStart"]},
                              UpdateExpression="SET #s = :a REMOVE appointmentId",
                              ExpressionAttributeNames={"#s": "status"}, ExpressionAttributeValues={":a": "AVAILABLE"})
            freed += 1
        print(f"Set {freed} booked slots back to AVAILABLE")


if __name__ == "__main__":
    main()
