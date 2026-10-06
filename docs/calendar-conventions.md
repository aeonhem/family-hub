# Calendar conventions

All on the **Family** calendar. The app and bot write these; anyone can also
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
