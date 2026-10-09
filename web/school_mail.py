"""Arcadia school emails for the parents, read from Julian's Gmail.

Read-only: the token (gmail_token.json, made by bot/authorize.py --gmail) can
list and read mail but not send, delete or change anything. Only messages
from an @arcadia.qld.edu.au address are ever fetched.
"""
from __future__ import annotations

import base64
import html
import re
from dataclasses import dataclass
from datetime import datetime
from email.utils import parseaddr
from zoneinfo import ZoneInfo

DOMAIN = "arcadia.qld.edu.au"
SCOPES = ["https://www.googleapis.com/auth/gmail.readonly"]
SUMMARY_CHARS = 280
BODY_CHARS = 4000


@dataclass
class Email:
    id: str
    thread_id: str
    sender: str
    address: str
    subject: str
    received: datetime
    summary: str
    body: str


def from_school(address: str) -> bool:
    domain = address.rsplit("@", 1)[-1].lower() if "@" in address else ""
    return domain == DOMAIN or domain.endswith("." + DOMAIN)


def _decode(data: str) -> str:
    return base64.urlsafe_b64decode(data + "=" * (-len(data) % 4)).decode("utf-8", "replace")


def _find(part: dict, mime: str) -> str | None:
    """First body of type [mime] in a Gmail payload, skipping attachments."""
    if part.get("mimeType") == mime and part.get("body", {}).get("data") and not part.get("filename"):
        return _decode(part["body"]["data"])
    for child in part.get("parts", []) or []:
        found = _find(child, mime)
        if found:
            return found
    return None


def html_to_text(raw: str) -> str:
    raw = re.sub(r"(?is)<(script|style|head)\b.*?</\1>", " ", raw)
    raw = re.sub(r"(?i)<br\s*/?>|</(p|div|li|tr|h[1-6])>", "\n", raw)
    return html.unescape(re.sub(r"<[^>]+>", " ", raw))


# Greetings and banners that say nothing about what the email is for.
_FILLER = re.compile(
    r"^(dear\b|hi\b|hello\b|good (morning|afternoon|evening)\b|greetings\b|to (all )?(parents|families)\b"
    r"|kind regards|regards|thanks|thank you|cheers|warm regards|\[?(external|caution)\b|view (this|in) (email|browser))",
    re.I,
)


def clean_text(text: str) -> str:
    lines = []
    for line in text.replace("\r", "").split("\n"):
        line = re.sub(r"[ \t ​]+", " ", line).strip()
        if line.startswith(">"):  # quoted reply
            continue
        if re.match(r"^(on .+ wrote:|-+ ?(original|forwarded) message ?-+)$", line, re.I):
            break
        lines.append(line)
    return re.sub(r"\n{3,}", "\n\n", "\n".join(lines)).strip()


def summarise(text: str, limit: int = SUMMARY_CHARS) -> str:
    """The first sentences that carry the point, skipping greetings."""
    lines = [l for l in text.split("\n") if l and not (_FILLER.match(l) and len(l) < 80)]
    flat = " ".join(lines)
    if len(flat) <= limit:
        return flat
    cut = flat[:limit]
    end = max(cut.rfind(". "), cut.rfind("! "), cut.rfind("? "))
    return cut[: end + 1] if end > limit // 3 else cut.rsplit(" ", 1)[0] + "…"


def parse_message(msg: dict, tz: ZoneInfo) -> Email:
    headers = {h["name"].lower(): h["value"] for h in msg.get("payload", {}).get("headers", [])}
    name, address = parseaddr(headers.get("from", ""))
    payload = msg.get("payload", {})
    plain = _find(payload, "text/plain")
    text = clean_text(plain if plain and plain.strip() else html_to_text(_find(payload, "text/html") or ""))
    if not text:
        text = html.unescape(msg.get("snippet", ""))
    return Email(
        id=msg["id"],
        thread_id=msg.get("threadId", msg["id"]),
        sender=name or address,
        address=address.lower(),
        subject=headers.get("subject", "").strip() or "(no subject)",
        received=datetime.fromtimestamp(int(msg.get("internalDate", 0)) / 1000, tz),
        summary=summarise(text),
        body=text[:BODY_CHARS],
    )


class Mailbox:
    """Fetches recent school emails. Messages never change, so each one is
    downloaded once and kept in memory."""

    def __init__(self, service, tz: ZoneInfo):
        self.service = service
        self.tz = tz
        self.cache: dict[str, Email] = {}

    def recent(self, days: int) -> list[Email]:
        resp = self.service.users().messages().list(
            userId="me", q=f"from:{DOMAIN} newer_than:{days}d", maxResults=100,
        ).execute()
        out = []
        for ref in resp.get("messages", []):
            if ref["id"] not in self.cache:
                msg = self.service.users().messages().get(userId="me", id=ref["id"], format="full").execute()
                self.cache[ref["id"]] = parse_message(msg, self.tz)
            email = self.cache[ref["id"]]
            if from_school(email.address):  # the search is loose; check the real sender
                out.append(email)
        return sorted(out, key=lambda e: e.received, reverse=True)
