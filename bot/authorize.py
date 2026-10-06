"""One-time Google sign-in for the bot. Run on a computer with a browser:

    pip install google-auth-oauthlib
    python authorize.py client_secret.json

Sign in as the account that owns the Family calendar. It writes token.json;
copy that file next to bot.py on the server.
"""
import sys

from google_auth_oauthlib.flow import InstalledAppFlow

SCOPES = ["https://www.googleapis.com/auth/calendar"]

if __name__ == "__main__":
    secrets = sys.argv[1] if len(sys.argv) > 1 else "client_secret.json"
    creds = InstalledAppFlow.from_client_secrets_file(secrets, SCOPES).run_local_server(port=0)
    with open("token.json", "w") as f:
        f.write(creds.to_json())
    print("Saved token.json. Copy it to the server next to bot.py.")
