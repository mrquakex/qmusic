"""Sends the "new version" push message to every phone that has QMusic, straight from this computer.

Use it right after publishing a release (the GitHub Actions workflow does the same when its account is in good standing):

    python notify-release.py <path-to-firebase-service-account.json> <version>

Needs only Python and OpenSSL (both come with Git for Windows). The key file is read here and never sent anywhere
except to Google, to get a short-lived access token.
"""
import base64
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.request


def b64(data: bytes) -> bytes:
    return base64.urlsafe_b64encode(data).rstrip(b"=")


def access_token(account: dict) -> str:
    now = int(time.time())
    header = b64(json.dumps({"alg": "RS256", "typ": "JWT"}).encode())
    claims = b64(json.dumps({
        "iss": account["client_email"],
        "scope": "https://www.googleapis.com/auth/firebase.messaging",
        "aud": "https://oauth2.googleapis.com/token",
        "iat": now,
        "exp": now + 3600,
    }).encode())
    signing_input = header + b"." + claims

    key_file = tempfile.NamedTemporaryFile("w", suffix=".pem", delete=False)
    try:
        key_file.write(account["private_key"])
        key_file.close()
        signature = subprocess.run(
            ["openssl", "dgst", "-sha256", "-sign", key_file.name],
            input=signing_input, capture_output=True, check=True,
        ).stdout
    finally:
        os.unlink(key_file.name)

    assertion = (signing_input + b"." + b64(signature)).decode()
    body = f"grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion={assertion}".encode()
    request = urllib.request.Request("https://oauth2.googleapis.com/token", data=body)
    with urllib.request.urlopen(request) as response:
        return json.load(response)["access_token"]


def main() -> None:
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    key_path, version = sys.argv[1], sys.argv[2].lstrip("v")
    with open(key_path, encoding="utf-8") as f:
        account = json.load(f)

    message = {"message": {"topic": "releases", "data": {"type": "update", "version": version}, "android": {"priority": "HIGH"}}}
    request = urllib.request.Request(
        f"https://fcm.googleapis.com/v1/projects/{account['project_id']}/messages:send",
        data=json.dumps(message).encode(),
        headers={"Authorization": f"Bearer {access_token(account)}", "Content-Type": "application/json"},
    )
    with urllib.request.urlopen(request) as response:
        print("Sent:", response.read().decode())


if __name__ == "__main__":
    main()
