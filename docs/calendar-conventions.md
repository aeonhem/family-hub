# Calendar conventions

All on the **Family** calendar. The app and bot write these; anyone can also
create them by hand in Google Calendar using the same title prefix.

| Thing | Event shape | Example title |
|---|---|---|
| Event | Normal event | `Erlina swimming` |
| Dinner | Event at dinner time on that night | `🍽 Spaghetti bolognese` |
| Chore (open) | All-day event on the due day | `☐ Take the bins out` |
| Chore (done) | Same event, prefix flipped | `✅ Take the bins out` |
| Memo | 5-minute event at send time, popup reminder at 0 min | `📣 Pick up milk on the way home` |
| Note | All-day event on the day written | `📝 Wi-Fi password changed` |

Extra structure goes in the event's private `extendedProperties`, which
Google Calendar keeps but doesn't display:

| Key | Used on | Values |
|---|---|---|
| `fh_type` | all | `dinner`, `chore`, `memo`, `note` |
| `fh_for` | chore, memo | `julian`, `sally`, `erlina`, `all` |
| `fh_done_by` | chore | who ticked it |
| `fh_from` | memo, note | who sent it |

The title prefix is the source of truth so hand-made events still work;
`extendedProperties` just make filtering cheap and reliable.
