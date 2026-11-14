"""Shared helpers for the seed, reset, check and experiment scripts.

Every script reads the deployed stack's outputs (API URL, user pool, table names) from CloudFormation,
so nothing has to be copied by hand. AWS credentials come from your normal AWS CLI configuration
(or from CloudShell, where they are already set).
"""
from __future__ import annotations

import json
import os
import secrets
import string
import sys
import time
from pathlib import Path
from typing import Any

import boto3
import requests

REGION = os.environ.get("AWS_REGION", "ap-south-1")
ROOT = Path(__file__).resolve().parent.parent
SEED_DIR = ROOT / "scripts" / ".seed"          # git-ignored: holds generated test passwords
SEED_FILE = SEED_DIR / "seed-output.json"


def stack_outputs(stack: str, region: str = REGION) -> dict[str, str]:
    cfn = boto3.client("cloudformation", region_name=region)
    stacks = cfn.describe_stacks(StackName=stack)["Stacks"]
    return {o["OutputKey"]: o["OutputValue"] for o in stacks[0].get("Outputs", [])}


def generate_password() -> str:
    """Random password that satisfies the user pool policy (upper, lower, digit, length >= 8)."""
    alphabet = string.ascii_letters + string.digits
    while True:
        pwd = "".join(secrets.choice(alphabet) for _ in range(14)) + "a1A"
        if any(c.isupper() for c in pwd) and any(c.islower() for c in pwd) and any(c.isdigit() for c in pwd):
            return pwd


def login(client_id: str, email: str, password: str, region: str = REGION) -> str:
    """Cognito USER_PASSWORD_AUTH. Returns the access token (valid 60 minutes)."""
    cognito = boto3.client("cognito-idp", region_name=region)
    for attempt in range(5):
        try:
            res = cognito.initiate_auth(
                ClientId=client_id,
                AuthFlow="USER_PASSWORD_AUTH",
                AuthParameters={"USERNAME": email, "PASSWORD": password},
            )
            return res["AuthenticationResult"]["AccessToken"]
        except cognito.exceptions.TooManyRequestsException:
            time.sleep(1 + attempt)
    raise RuntimeError(f"Could not log in {email}: Cognito kept throttling")


class Api:
    """Tiny HTTP client for the deployed API."""

    def __init__(self, base_url: str):
        self.base_url = base_url.rstrip("/")
        self.session = requests.Session()

    def call(self, method: str, path: str, token: str | None = None, body: Any = None,
             params: dict | None = None, headers: dict | None = None) -> tuple[int, Any, dict]:
        h = {"Accept": "application/json"}
        if token:
            h["Authorization"] = f"Bearer {token}"
        if headers:
            h.update(headers)
        res = self.session.request(method, self.base_url + path, json=body, params=params, headers=h, timeout=35)
        try:
            data = res.json() if res.content else None
        except ValueError:
            data = res.text
        return res.status_code, data, dict(res.headers)


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2), encoding="utf-8")


def read_seed() -> dict:
    if not SEED_FILE.exists():
        sys.exit(f"{SEED_FILE} not found. Run: python scripts/seed.py --stack <stack> first.")
    return json.loads(SEED_FILE.read_text(encoding="utf-8"))
