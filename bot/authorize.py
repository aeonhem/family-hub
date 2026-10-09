"""One-time Google sign-in for the bot. Run on a computer with a browser:

    pip install google-auth-oauthlib
    python authorize.py client_secret.json

Sign in as the account that owns the Family calendar. It writes token.json;
copy that file next to bot.py on the server.

For the parents' school emails, sign in again with --gmail. That writes a
separate gmail_token.json that can only read mail (see web/README.md):

    python authorize.py client_secret.json --gmail
"""
import sys

from google_auth_oauthlib.flow import InstalledAppFlow

# Events only: enough to read and write the Family calendar, but can't
# change who calendars are shared with.
CALENDAR = (["https://www.googleapis.com/auth/calendar.events"], "token.json")
# Read-only mail: can't send, delete or change anything.
GMAIL = (["https://www.googleapis.com/auth/gmail.readonly"], "gmail_token.json")

if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    scopes, out = GMAIL if "--gmail" in sys.argv else CALENDAR
    secrets = args[0] if args else "client_secret.json"
    creds = InstalledAppFlow.from_client_secrets_file(secrets, scopes).run_local_server(port=0)
    with open(out, "w") as f:
        f.write(creds.to_json())
    print(f"Saved {out}. Copy it to the server.")
