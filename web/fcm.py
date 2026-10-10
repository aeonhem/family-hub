"""Memo alerts to the Android app through Firebase Cloud Messaging (free).

The app's own background check can sleep for a long time while a phone is
idle; a high-priority FCM message wakes it straight away. Turned on by putting
the Firebase service account key at STATE_DIR/fcm_service_account.json (see
web/README.md). Blocking calls: run them off the event loop.
"""
from __future__ import annotations

import json
import logging
import urllib.error
import urllib.request
from pathlib import Path

log = logging.getLogger("familyhub-web.fcm")

SCOPE = "https://www.googleapis.com/auth/firebase.messaging"


class Gone(Exception):
    """The phone's token is no longer valid (app removed or reinstalled)."""


def message(token: str, data: dict[str, str]) -> dict:
    """A data-only message, so the app decides whether to show it (and can skip
    a memo its own check already showed). High priority wakes an idle phone."""
    return {"message": {
        "token": token,
        "data": {k: str(v) for k, v in data.items()},
        "android": {"priority": "HIGH", "ttl": "43200s"},
    }}


def is_gone(status: int, body: str) -> bool:
    if status == 404:
        return True
    if status != 400:
        return False
    # A malformed or stale token comes back as INVALID_ARGUMENT on the token field.
    return "registration token" in body.lower() or "UNREGISTERED" in body


class Sender:
    def __init__(self, key_file: Path):
        from google.oauth2 import service_account

        self.creds = service_account.Credentials.from_service_account_file(str(key_file), scopes=[SCOPE])
        self.project = json.loads(key_file.read_text())["project_id"]

    def _bearer(self) -> str:
        if not self.creds.valid:
            import google_auth_httplib2
            import httplib2

            self.creds.refresh(google_auth_httplib2.Request(httplib2.Http(timeout=15)))
        return self.creds.token

    def send(self, token: str, data: dict[str, str]) -> None:
        req = urllib.request.Request(
            f"https://fcm.googleapis.com/v1/projects/{self.project}/messages:send",
            data=json.dumps(message(token, data)).encode(),
            headers={"Authorization": f"Bearer {self._bearer()}", "Content-Type": "application/json"},
        )
        try:
            urllib.request.urlopen(req, timeout=15).close()
        except urllib.error.HTTPError as e:
            body = e.read().decode(errors="replace")
            if is_gone(e.code, body):
                raise Gone() from e
            raise RuntimeError(f"FCM {e.code}: {body[:300]}") from e
