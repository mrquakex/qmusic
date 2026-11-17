"""Sends the "new version" push message to every phone that has QMusic, straight from this computer.

Use it right after publishing a release (the GitHub Actions workflow does the same when its account is in good standing):

    python notify-release.py <path-to-firebase-service-account.json> <version> [release-notes.md]

With the notes file, what is new goes with the message and shows in the phone's notification panel.

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
    if len(sys.argv) not in (3, 4):
        sys.exit(__doc__)
    key_path, version = sys.argv[1], sys.argv[2].lstrip("v")
    body = ""
    if len(sys.argv) == 4:
        with open(sys.argv[3], encoding="utf-8") as f:
            lines = [l.strip() for l in f if l.strip()]
        # One line per change, cut to what a notification shows (the whole list is in the app).
        body = "\n".join("• " + l.lstrip("-* ").split(": ")[0].split(". ")[0][:90] for l in lines)[:900]
    with open(key_path, encoding="utf-8") as f:
        account = json.load(f)

    data = {"type": "update", "version": version}
    if body:
        data["body"] = body
    message = {"message": {"topic": "releases", "data": data, "android": {"priority": "HIGH"}}}
    request = urllib.request.Request(
        f"https://fcm.googleapis.com/v1/projects/{account['project_id']}/messages:send",
        data=json.dumps(message).encode(),
        headers={"Authorization": f"Bearer {access_token(account)}", "Content-Type": "application/json"},
    )
    with urllib.request.urlopen(request) as response:
        print("Sent:", response.read().decode())


if __name__ == "__main__":
    main()
