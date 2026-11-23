# Calendar conventions

All on the **Family** calendar, except chores: those go on the **Family
Chores** calendar when there is one, so each person can turn off chore alerts
for that calendar only. The app, web app and bot read chores from both, so a
chore on either one works. The app and bot write these; anyone can also
create them by hand in Google Calendar using the same title prefix.

| Thing | Event shape | Example title |
|---|---|---|
| Event | Normal event | `Erlina swimming` |
| Dinner | Event at dinner time on that night | `🍽 Spaghetti bolognese` |
| Chore (open) | All-day event on the due day | `☐ Take the bins out` |
| Chore (done) | Same event, prefix flipped | `✅ Take the bins out` |
| Memo | 5-minute event starting a minute after sending, no reminder (the app and bot notify) | `📣 Pick up milk on the way home` |
| Note | All-day event on the day written | `📝 Wi-Fi password changed` |

Extra structure goes in the event description as `key: value` lines, one per
line, which anyone can read or edit in Google Calendar:

| Key | Used on | Values |
|---|---|---|
| `for` | chore, memo | `Julian`, `Sally`, `Erlina`, `Everyone` (blank means everyone) |
| `from` | chore, memo, note | who created it |
| `cook` | dinner | who is cooking, or `Takeaway` |
| `done-by` | chore | who ticked it |

Example chore description:

```
for: Erlina
from: Sally
```

The title prefix decides what kind of thing an event is, so events made by
hand in Google Calendar still work. (Private `extendedProperties` were the
first idea, but Android apps can't write those through the phone's calendar.)

Repeating events work: ticking or editing one occurrence changes only that
day, the same as "This event" in Google Calendar.

## Missed chores

An open (`☐`) chore whose day has passed is cleared just after midnight
(Brisbane time): the bot deletes it from the calendar, and the app, web app
and bot stop showing it from midnight even before that. It is deleted, never
ticked, so it doesn't count towards Erlina's week. For a repeating chore only
that day's occurrence goes.

## Repeating chores

A repeating chore is one all-day `☐` event with an RRULE, e.g.
`FREQ=DAILY;INTERVAL=2` for every two days. Parents add one in the Android
app (Add a chore, then Repeat) and stop it under "Repeating chores", which
sets `UNTIL` to today so today's occurrence stays and later ones go. Ticking
one day changes only that occurrence.
