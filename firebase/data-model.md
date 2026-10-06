# Firestore data model (draft)

All documents live under `families/{familyId}/` so the family is one unit.

| Collection | Fields |
|---|---|
| `members` | name, role (parent/child), fcmToken?, discordUserId? |
| `meals` | date (YYYY-MM-DD), title, time, cook?, notes? |
| `chores` | title, assignee, due?, done, doneBy?, doneAt? |
| `notes` | title, body, author, pinned, updatedAt |
| `memos` | body, from, to[] (member ids or "all"), sentAt, readBy[] |
