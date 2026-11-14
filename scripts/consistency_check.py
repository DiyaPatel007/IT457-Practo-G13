"""Experiment 4 ground truth: check the booking invariant directly in DynamoDB (not from HTTP responses).

Invariant: for every slot, at most one non-cancelled appointment refers to it, and the slot is BOOKED
if and only if exactly that appointment exists and the slot's appointmentId points at it.

Usage:
  python scripts/consistency_check.py --stack practo-lite [--out results/exp4-race/run1/consistency.json]
Exit code 1 if any violation is found.
"""
from __future__ import annotations

import argparse
import json
import sys
from collections import defaultdict
from pathlib import Path

import boto3

from practo_common import REGION, stack_outputs


def scan_all(table):
    res = table.scan(ConsistentRead=True)
    yield from res["Items"]
    while "LastEvaluatedKey" in res:
        res = table.scan(ConsistentRead=True, ExclusiveStartKey=res["LastEvaluatedKey"])
        yield from res["Items"]


def check(slots: list[dict], appointments: list[dict]) -> dict:
    active = defaultdict(list)
    for a in appointments:
        if a.get("status") != "CANCELLED":
            active[(a["doctorId"], a["slotStart"])].append(a["appointmentId"])
    violations = []
    booked = 0
    for s in slots:
        key = (s["doctorId"], s["slotStart"])
        ids = active.pop(key, [])
        if s.get("status") == "BOOKED":
            booked += 1
        if len(ids) > 1:
            violations.append({"type": "DOUBLE_BOOKING", "slot": key, "appointments": ids})
        elif s.get("status") == "BOOKED" and (len(ids) != 1 or s.get("appointmentId") != ids[0]):
            violations.append({"type": "BOOKED_SLOT_WITHOUT_MATCHING_APPOINTMENT", "slot": key,
                               "slotAppointmentId": s.get("appointmentId"), "appointments": ids})
        elif s.get("status") == "AVAILABLE" and ids:
            violations.append({"type": "APPOINTMENT_ON_AVAILABLE_SLOT", "slot": key, "appointments": ids})
    for key, ids in active.items():
        violations.append({"type": "APPOINTMENT_WITHOUT_SLOT", "slot": key, "appointments": ids})
    return {
        "slots": len(slots),
        "bookedSlots": booked,
        "appointments": len(appointments),
        "activeAppointments": sum(1 for a in appointments if a.get("status") != "CANCELLED"),
        "violations": len(violations),
        "doubleBookedSlots": sum(1 for v in violations if v["type"] == "DOUBLE_BOOKING"),
        "details": [{**v, "slot": list(v["slot"])} for v in violations],
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--stack", default="practo-lite")
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()

    out = stack_outputs(args.stack)
    db = boto3.resource("dynamodb", region_name=REGION)
    report = check(list(scan_all(db.Table(out["SlotsTableName"]))),
                   list(scan_all(db.Table(out["AppointmentsTableName"]))))
    text = json.dumps(report, indent=2)
    print(text)
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(text, encoding="utf-8")
    sys.exit(1 if report["violations"] else 0)


if __name__ == "__main__":
    main()
