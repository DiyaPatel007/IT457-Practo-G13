"""Create the four tables in DynamoDB Local for running a service on your laptop.

Start DynamoDB Local first, e.g.:  docker run -p 8000:8000 amazon/dynamodb-local
Then:                              python scripts/local_tables.py --endpoint http://localhost:8000
Key schemas mirror infra/template.yaml and test-support/LocalTables.java.
"""
from __future__ import annotations

import argparse

import boto3


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--endpoint", default="http://localhost:8000")
    parser.add_argument("--prefix", default="practo-lite")
    args = parser.parse_args()
    db = boto3.client("dynamodb", endpoint_url=args.endpoint, region_name="ap-south-1",
                      aws_access_key_id="local", aws_secret_access_key="local")

    def s(name):
        return {"AttributeName": name, "AttributeType": "S"}

    def key(h, r=None):
        k = [{"AttributeName": h, "KeyType": "HASH"}]
        return k + ([{"AttributeName": r, "KeyType": "RANGE"}] if r else [])

    def gsi(name, h, r):
        return {"IndexName": name, "KeySchema": key(h, r), "Projection": {"ProjectionType": "ALL"}}

    tables = [
        (f"{args.prefix}-Users", [s("userId")], key("userId"), []),
        (f"{args.prefix}-Doctors", [s("doctorId"), s("searchCity"), s("specKey")], key("doctorId"),
         [gsi("search-index", "searchCity", "specKey")]),
        (f"{args.prefix}-Slots", [s("doctorId"), s("slotStart")], key("doctorId", "slotStart"), []),
        (f"{args.prefix}-Appointments", [s("appointmentId"), s("patientId"), s("doctorId"), s("slotStart")],
         key("appointmentId"), [gsi("patient-index", "patientId", "slotStart"),
                                gsi("doctor-index", "doctorId", "slotStart")]),
    ]
    existing = set(db.list_tables()["TableNames"])
    for name, attrs, ks, gsis in tables:
        if name in existing:
            print(f"exists: {name}")
            continue
        kwargs = {"TableName": name, "AttributeDefinitions": attrs, "KeySchema": ks, "BillingMode": "PAY_PER_REQUEST"}
        if gsis:
            kwargs["GlobalSecondaryIndexes"] = gsis
        db.create_table(**kwargs)
        print(f"created: {name}")


if __name__ == "__main__":
    main()
