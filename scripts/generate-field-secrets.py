#!/usr/bin/env python3
"""Generate field secrets for Praxis admin. Print to stdout; do not commit output."""
import argparse
import base64
import os
import secrets
import sys


def b64_key(nbytes: int = 32) -> str:
    return base64.b64encode(os.urandom(nbytes)).decode("ascii")


def token(nbytes: int = 32) -> str:
    return secrets.token_urlsafe(nbytes)


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate WORKDSH_* field secrets")
    parser.add_argument("--bootstrap-email", default="admin", help="Bootstrap owner login (default: admin)")
    parser.add_argument("--bootstrap-password", default="Sun55kong!", help="Bootstrap owner password (default: Sun55kong!)")
    parser.add_argument("--origin", default="", help="e.g. https://admin.example.com (optional)")
    parser.add_argument("--jdbc-url", default="", help="PostgreSQL JDBC URL (optional placeholder)")
    args = parser.parse_args()

    password = args.bootstrap_password.strip() or "Sun55kong!"
    if len(password) < 8:
        print("bootstrap password must be at least 8 characters", file=sys.stderr)
        return 2

    lines = [
        "# Field secrets for kaiwu-praxis-admin — store offline, do not commit",
        "SPRING_PROFILES_ACTIVE=enterprise-postgres",
        f"JDBC_URL={args.jdbc_url or 'jdbc:postgresql://127.0.0.1:5432/kaiwu_praxis'}",
        "JDBC_USER=workdsh_app",
        "JDBC_PASSWORD=<set-app-role-password>",
        "BIND_ADDRESS=0.0.0.0",
        "PORT=18890",
        f"WORKDSH_ADMIN_WEB_ORIGIN={args.origin}",
        f"WORKDSH_BOOTSTRAP_ADMIN_EMAIL={args.bootstrap_email}",
        f"WORKDSH_BOOTSTRAP_ADMIN_PASSWORD={password}",
        "WORKDSH_BOOTSTRAP_REQUIRE_PASSWORD_CHANGE=false",
        f"WORKDSH_SECRETS_ENCRYPTION_KEY={b64_key(32)}",
        f"WORKDSH_SECRETS_SERVICE_KEY={token(32)}",
        "WORKDSH_LICENSE_ENFORCED=true",
        "",
        "# Backup these three offline:",
        "# 1) WORKDSH_SECRETS_ENCRYPTION_KEY",
        "# 2) WORKDSH_SECRETS_SERVICE_KEY",
        "# 3) license private key path (WORKDSH_LICENSE_PRIVATE_KEY on signing machine only)",
    ]
    sys.stdout.write("\n".join(lines) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
