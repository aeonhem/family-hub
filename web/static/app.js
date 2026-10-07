// Family Hub web app: the Android app's screens (android/.../ui/Screens.kt)
// for a browser, talking to web/server.py.
'use strict';

const FAMILY = ['Julian', 'Sally', 'Erlina'];
const DINNER_TIMES = ['17:30', '18:00', '18:30', '19:00'];
const DEFAULT_DINNER_TIME = '18:00';
const REFRESH_MS = 60 * 1000;

// Material outlined icons, the same ones the Android app uses.
const ICON = {
  today: 'M19 3h-1V1h-2v2H8V1H6v2H5c-1.11 0-1.99.9-1.99 2L3 19a2 2 0 0 0 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 16H5V9h14v10zm0-12H5V5h14v2zM7 11h5v5H7z',
  dinner: 'M16 6v8h3v8h2V2c-2.76 0-5 2.24-5 4zm-5 3H9V2H7v7H5V2H3v7c0 2.21 1.79 4 4 4v9h2v-9c2.21 0 4-1.79 4-4V2h-2v7z',
  chores: 'M22 5.18 10.59 16.6l-4.24-4.24 1.41-1.41 2.83 2.83 10-10L22 5.18zm-2.21 5.04c.13.57.21 1.17.21 1.78 0 4.42-3.58 8-8 8s-8-3.58-8-8 3.58-8 8-8c1.58 0 3.04.46 4.28 1.25l1.44-1.44A9.9 9.9 0 0 0 12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10c0-1.19-.22-2.33-.6-3.39l-1.61 1.61z',
  memos: 'M18 11v2h4v-2h-4zm-2 6.61c.96.71 2.21 1.65 3.2 2.39.4-.53.8-1.07 1.2-1.6-.99-.74-2.24-1.68-3.2-2.4-.4.54-.8 1.08-1.2 1.61zM20.4 5.6c-.4-.53-.8-1.07-1.2-1.6-.99.74-2.24 1.68-3.2 2.4.4.53.8 1.07 1.2 1.6.96-.72 2.21-1.65 3.2-2.4zM4 9c-1.1 0-2 .9-2 2v2c0 1.1.9 2 2 2h1v4h2v-4h1l5 3V6L8 9H4zm5.03 1.71L11 9.53v4.94l-1.97-1.18-.48-.29H4v-2h4.55l.48-.29zM15.5 12c0-1.33-.58-2.53-1.5-3.35v6.69c.92-.81 1.5-2.01 1.5-3.34z',
  check: 'M9 16.17 4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z',
};
const svg = (name) => `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="${ICON[name]}"/></svg>`;

const TABS = [
  { id: 'today', label: 'Today', heading: 'Today', icon: 'today' },
  { id: 'dinner', label: 'Dinner', heading: 'Dinner this week', icon: 'dinner' },
  { id: 'chores', label: 'Chores', heading: 'Chores', icon: 'chores' },
  { id: 'memos', label: 'Memos', heading: 'Memos', icon: 'memos' },
];

// ---------- small helpers ----------

const store = {
  get(k) { try { return localStorage.getItem(k); } catch (_) { return null; } },
  set(k, v) { try { v == null ? localStorage.removeItem(k) : localStorage.setItem(k, v); } catch (_) { /* private mode */ } },
};

const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

// Dates are 'YYYY-MM-DD' strings in the family's time zone (the server says
// which day it is), so they compare as strings and never shift with the phone.
const DOW = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];
const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
const D = {
  parse(s) { const [y, m, d] = s.split('-').map(Number); return new Date(Date.UTC(y, m - 1, d)); },
  add(s, n) { return new Date(D.parse(s).getTime() + n * 864e5).toISOString().slice(0, 10); },
  dow(s) { return D.parse(s).getUTCDay(); },
  monday(s) { return D.add(s, -((D.dow(s) + 6) % 7)); },
  dayName(s) { return DOW[D.dow(s)]; },
  short(s) { return DOW[D.dow(s)].slice(0, 3); },
  num(s) { return D.parse(s).getUTCDate(); },
  long(s) { return `${D.dayName(s)} ${D.num(s)} ${MONTHS[D.parse(s).getUTCMonth()]}`; },
  medium(s) { return `${D.dayName(s)} ${D.num(s)} ${MONTHS[D.parse(s).getUTCMonth()].slice(0, 3)}`; },
};
// '18:30' -> '6:30pm', like the Android app.
function pretty(t) {
  const [h, m] = t.split(':').map(Number);
  return `${h % 12 || 12}:${String(m).padStart(2, '0')}${h < 12 ? 'am' : 'pm'}`;
}
const covers = (i, d) => i.day <= d && d <= i.end_day;
const isEveryone = (w) => !w || ['everyone', 'all'].includes(w.toLowerCase());

// ---------- state ----------

const S = {
  token: store.get('token'),
  me: store.get('me'),
  tab: new URLSearchParams(location.search).get('tab') || store.get('tab') || 'today',
  today: null,
  items: [],
  loaded: false,
  dialog: null,
  memoBody: '',
  memoTo: 'Everyone',
  loginError: '',
  push: 'unknown', // unsupported | install | ask | on | denied
};
if (!TABS.some((t) => t.id === S.tab)) S.tab = 'today';

// ---------- server ----------

async function api(path, body) {
  const resp = await fetch(path, {
    method: body === undefined ? 'GET' : 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${S.token}` },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (resp.status === 401) {
    S.token = null; store.set('token', null); render();
    throw new Error('Passcode needed');
  }
  const data = await resp.json().catch(() => ({}));
  if (!resp.ok) throw new Error(data.error || `Something went wrong (${resp.status})`);
  return data;
}

let refreshing = null;
async function refresh() {
  if (!S.token || !S.me) return;
  if (refreshing) return refreshing;
  refreshing = api('/api/items')
    .then((d) => { S.today = d.today; S.items = d.items; S.loaded = true; render(); })
    .catch((e) => { if (S.token) snack(e.message); })
    .finally(() => { refreshing = null; });
  return refreshing;
}

async function write(path, body, okText) {
  try {
    await api(path, body);
    if (okText) snack(okText);
  } catch (e) {
    snack(e.message);
  }
  await refresh();
}

// ---------- screens ----------

function render() {
  const app = document.getElementById('app');
  // Keep the cursor where it was when a refresh redraws the page.
  const active = document.activeElement;
  const focusId = active && active.id;
  const sel = focusId && 'selectionStart' in active ? [active.selectionStart, active.selectionEnd] : null;

  if (!S.token) app.innerHTML = loginScreen();
  else if (!S.me) app.innerHTML = whoScreen();
  else app.innerHTML = mainScreen() + (S.dialog ? dialogHtml() : '');

  if (focusId) {
    const el = document.getElementById(focusId);
    if (el) {
      el.focus({ preventScroll: true });
      if (sel) try { el.setSelectionRange(sel[0], sel[1]); } catch (_) { /* not a text field */ }
    }
  }
  // Set here rather than in a style attribute, which the page's security policy blocks.
  app.querySelectorAll('[data-pct]').forEach((el) => { el.style.width = `${el.dataset.pct}%`; });
}

function loginScreen() {
  return `<div class="setup">
    <h1>Family Hub</h1>
    <p>Enter the family passcode to open dinner, chores and memos.</p>
    <form class="rows" data-form="login">
      <div class="field"><input id="passcode" type="password" autocomplete="current-password" placeholder=" " required><label for="passcode">Passcode</label></div>
      ${S.loginError ? `<div class="error-text">${esc(S.loginError)}</div>` : ''}
      <button class="btn block" type="submit">Open</button>
    </form>
  </div>`;
}

function whoScreen() {
  return `<div class="setup">
    <h1>Who's using this phone?</h1>
    <p>Memos and ticked chores will be signed with your name.</p>
    <div class="rows">${FAMILY.map((n) => `<button class="pick" data-action="me" data-name="${n}">${n}</button>`).join('')}</div>
  </div>`;
}

function mainScreen() {
  const tab = TABS.find((t) => t.id === S.tab);
  let body = '<p class="empty">Loading…</p>';
  if (S.loaded) body = { today: todayTab, dinner: dinnerTab, chores: choresTab, memos: memosTab }[S.tab]();
  return `<main class="main">
    <header class="header">
      <div class="date">${S.today ? D.long(S.today) : '&nbsp;'}</div>
      <h1>${tab.heading}</h1>
    </header>
    ${body}
  </main>
  <nav class="nav">${TABS.map((t) => `
    <button data-action="tab" data-tab="${t.id}" ${t.id === S.tab ? 'aria-current="page"' : ''}>
      <span class="pill-ind">${svg(t.icon)}</span>${t.label}
    </button>`).join('')}
  </nav>`;
}

// ---------- Today ----------

function todayTab() {
  const today = S.today;
  const dinner = S.items.find((i) => i.kind === 'dinner' && covers(i, today));
  const chores = choresFor((i) => covers(i, today) || (!i.done && i.end_day < today));
  const now = Date.now();
  // Anything not over yet, so a Mon-Fri camp shows every day it runs.
  const comingUp = S.items.filter((i) => i.kind === 'event' && i.day <= D.add(today, 7) &&
    (i.time ? i.ends_at > now : i.end_day >= today)).slice(0, 5);
  const memo = latest(S.items.filter((i) => i.kind === 'memo' && i.day >= D.add(today, -7)));
  const done = chores.filter((c) => c.done).length;

  return `<div class="stack">
    ${pushBanner()}
    <button class="dinner-hero" data-action="dinner" data-day="${today}">
      <span class="label">${svg('dinner')}Dinner tonight${dinner && dinner.time ? ` · ${pretty(dinner.time)}` : ''}</span>
      <span class="meal">${dinner ? esc(dinner.title) : 'Not planned yet'}</span>
      <span class="sub">${dinner && dinner.cook ? `${esc(dinner.cook)} is cooking` : dinner ? 'Tap to edit' : 'Tap to plan it'}</span>
    </button>

    <section class="card">
      <div class="card-head"><span class="card-title">Chores today</span><span class="small-muted">${done} of ${chores.length} done</span></div>
      <div class="progress"><div data-pct="${chores.length ? (100 * done) / chores.length : 0}"></div></div>
      ${chores.length ? chores.map((c) => choreRow(c)).join('') : '<div class="empty">Nothing to do today.</div>'}
    </section>

    <section class="card">
      <div class="card-title">Coming up</div>
      ${comingUp.length ? comingUp.map((e) => {
        const when = e.day === today && e.time ? pretty(e.time) : covers(e, today) ? 'Today' : D.short(e.day);
        return `<div class="upcoming"><span class="when">${when}</span><span>${esc(e.title)}</span></div>`;
      }).join('') : '<div class="empty">Nothing on this week.</div>'}
    </section>

    ${memo ? `<div class="memo-banner">${svg('memos')}<div class="body">
      <span class="meta">Latest memo · ${esc(memoMeta(memo))}</span><span class="text">${esc(memo.title)}</span>
    </div></div>` : ''}
  </div>`;
}

// ---------- Dinner ----------

function dinnerTab() {
  const today = S.today;
  const mon = D.monday(today);
  const week = [0, 1, 2, 3, 4, 5, 6].map((n) => D.add(mon, n));
  return `<div class="stack tight">${week.map((day) => {
    const d = dinnerOn(day);
    const detail = [d && d.cook, d && d.time && pretty(d.time), day === today ? 'tonight' : null].filter(Boolean);
    return `<button class="day-row ${day === today ? 'today' : ''}" data-action="dinner" data-day="${day}">
      <span class="dcol"><span class="dow">${D.short(day).toUpperCase()}</span><span class="dnum">${D.num(day)}</span></span>
      <span>
        <span class="meal ${d ? '' : 'none'}">${d ? esc(d.title) : 'Not planned yet'}</span>
        <span class="detail">${d ? esc(detail.join(' · ')) : 'Tap to add'}</span>
      </span>
    </button>`;
  }).join('')}</div>`;
}

function dinnerOn(day) {
  return S.items.find((i) => i.kind === 'dinner' && i.day === day);
}

// ---------- Chores ----------

function choresTab() {
  const today = S.today;
  const weekStart = D.monday(today);
  const weekEnd = D.add(weekStart, 6);
  const week = [0, 1, 2, 3, 4, 5, 6].map((n) => D.add(weekStart, n));
  const erlina = S.items.filter((i) => i.kind === 'chore' && (i.for || '').toLowerCase() === 'erlina');
  // Chores Erlina ticked herself this week, whoever they were for.
  const ticked = S.items.filter((i) => i.kind === 'chore' && i.done && (i.done_by || '').toLowerCase() === 'erlina' &&
    i.day <= weekEnd && i.end_day >= weekStart).length;
  const chores = choresFor((i) => (i.day <= weekEnd && i.end_day >= weekStart) || (!i.done && i.end_day < weekStart));

  return `<div class="stack">
    <section class="card">
      <div class="card-head"><span class="card-title">Erlina's week</span><span class="small-muted">${ticked} ticked</span></div>
      <div class="week">${week.map((d) => {
        const dayChores = erlina.filter((c) => c.day === d);
        const cls = !dayChores.length || d > today ? '' : dayChores.every((c) => c.done) ? 'all' : 'some';
        return `<div class="${cls}">${D.short(d)[0]}</div>`;
      }).join('')}</div>
    </section>

    <section class="card">
      <div class="card-title">All chores</div>
      ${chores.length ? chores.map((c) => choreRow(c, true)).join('') : '<div class="empty">No chores this week yet.</div>'}
    </section>

    <button class="btn-outline" data-action="add-chore">Add a chore</button>
  </div>`;
}

/** Chores matching keep, open ones first, then by day. */
function choresFor(keep) {
  return S.items.filter((i) => i.kind === 'chore' && keep(i))
    .sort((a, b) => (a.done - b.done) || a.day.localeCompare(b.day) || a.title.localeCompare(b.title));
}

function choreRow(c, showDay = false) {
  const today = S.today;
  const overdue = !c.done && c.end_day < today;
  const dayText = overdue ? 'Overdue' : covers(c, today) ? 'Today' : D.short(c.day);
  const sub = [c.for || 'Anyone', showDay || overdue ? dayText : null, c.done_by ? `ticked by ${c.done_by}` : null].filter(Boolean);
  return `<button class="chore ${c.done ? 'done' : ''}" role="checkbox" aria-checked="${c.done}" data-action="chore" data-id="${esc(c.id)}"
      aria-label="${c.done ? 'Untick' : 'Tick'} ${esc(c.title)}">
    <span class="box">${c.done ? svg('check') : ''}</span>
    <span><span class="name">${esc(c.title)}</span><span class="sub ${overdue ? 'overdue' : ''}">${esc(sub.join(' · '))}</span></span>
  </button>`;
}

// ---------- Memos ----------

function memosTab() {
  const memos = S.items.filter((i) => i.kind === 'memo').sort((a, b) => (b.start_at || 0) - (a.start_at || 0));
  const targets = ['Everyone', ...FAMILY.filter((n) => n !== S.me)];
  if (!targets.includes(S.memoTo)) S.memoTo = 'Everyone';
  return `<div class="stack tight">
    <section class="card">
      <div class="field"><textarea id="memo-body" placeholder=" " rows="3" maxlength="1000">${esc(S.memoBody)}</textarea><label for="memo-body">New memo</label></div>
      <div class="pills">${targets.map((n) => pill(n, S.memoTo === n, 'memo-to')).join('')}</div>
      <button class="btn block" data-action="send-memo" ${S.memoBody.trim() ? '' : 'disabled'}>Send to ${S.memoTo === 'Everyone' ? 'everyone' : S.memoTo}</button>
    </section>
    ${pushBanner()}
    ${memos.length ? memos.map((m) => `<div class="memo-card"><span class="meta">${esc(memoMeta(m, true))}</span><span class="text">${esc(m.title)}</span></div>`).join('')
      : '<p class="empty padded">No memos yet.</p>'}
    <div class="footnote">Using Family Hub as ${esc(S.me)} · <button class="link" data-action="switch-me">Change</button></div>
  </div>`;
}

function latest(memos) {
  return memos.reduce((best, m) => (!best || (m.start_at || 0) > (best.start_at || 0) ? m : best), null);
}

function memoMeta(m, withTo = false) {
  const today = S.today;
  const when = m.day === today && m.time ? pretty(m.time) : m.day === D.add(today, -1) ? 'Yesterday' : D.dayName(m.day);
  const who = m.from || 'Someone';
  const to = isEveryone(m.for) ? 'everyone' : m.for;
  return withTo ? `${who} to ${to} · ${when}` : `${who}, ${when}`;
}

// ---------- dialogs ----------

function pill(label, selected, action, value = label) {
  return `<button class="pill" aria-pressed="${selected}" data-action="${action}" data-value="${esc(value)}">${esc(label)}</button>`;
}

function dialogHtml() {
  const d = S.dialog;
  if (d.type === 'dinner') {
    const times = [...new Set([...DINNER_TIMES, d.time])].sort();
    return `<div class="scrim" data-action="close-dialog"><div class="dialog" role="dialog" aria-modal="true" aria-labelledby="dlg-title">
      <h2 id="dlg-title">Dinner on ${D.medium(d.day)}</h2>
      <div class="body">
        <div class="field"><input id="meal" placeholder=" " value="${esc(d.meal)}" maxlength="200" autocomplete="off"><label for="meal">What's for dinner</label></div>
        <div class="field-label">Time</div>
        <div class="pills">${times.map((t) => pill(pretty(t), d.time === t, 'dinner-time', t)).join('')}</div>
        <div class="field-label">Who's cooking</div>
        <div class="pills">${[...FAMILY, 'Takeaway'].map((n) => pill(n, d.cook === n, 'dinner-cook')).join('')}</div>
      </div>
      <div class="actions">
        <button class="btn-text" data-action="close-dialog">Cancel</button>
        <button class="btn round" data-action="save-dinner" ${d.meal.trim() ? '' : 'disabled'}>Save</button>
      </div>
    </div></div>`;
  }
  const today = S.today;
  const days = [0, 1, 2, 3, 4, 5, 6].map((n) => D.add(today, n));
  const label = (day) => (day === today ? 'Today' : day === D.add(today, 1) ? 'Tomorrow' : `${D.short(day)} ${D.num(day)}`);
  return `<div class="scrim" data-action="close-dialog"><div class="dialog" role="dialog" aria-modal="true" aria-labelledby="dlg-title">
    <h2 id="dlg-title">Add a chore</h2>
    <div class="body">
      <div class="field"><input id="chore-title" placeholder=" " value="${esc(d.title)}" maxlength="200" autocomplete="off"><label for="chore-title">Chore</label></div>
      <div class="field-label">Who</div>
      <div class="pills">${[...FAMILY, 'Anyone'].map((n) => pill(n, d.who === n, 'chore-who')).join('')}</div>
      <div class="field-label">When</div>
      <div class="pills">${days.map((day) => pill(label(day), d.day === day, 'chore-day', day)).join('')}</div>
    </div>
    <div class="actions">
      <button class="btn-text" data-action="close-dialog">Cancel</button>
      <button class="btn round" data-action="save-chore" ${d.title.trim() ? '' : 'disabled'}>Add</button>
    </div>
  </div></div>`;
}

// ---------- actions ----------

const actions = {
  tab(el) {
    S.tab = el.dataset.tab; store.set('tab', S.tab);
    history.replaceState(null, '', '/');
    render(); window.scrollTo(0, 0);
  },
  me(el) { S.me = el.dataset.name; store.set('me', S.me); render(); refresh(); syncPush(); },
  'switch-me'() { S.me = null; store.set('me', null); render(); },

  chore(el) {
    const c = S.items.find((i) => i.id === el.dataset.id);
    if (!c) return;
    // Flip it on screen straight away; the calendar write follows.
    c.done = !c.done;
    c.done_by = c.done ? S.me : null;
    render();
    let okText = null;
    if (c.done) {
      const todays = choresFor((i) => covers(i, S.today) && (i.for || '').toLowerCase() === S.me.toLowerCase());
      if (todays.some((i) => i.id === c.id)) okText = `Nice! ${todays.filter((i) => i.done).length} of ${todays.length} done`;
    }
    write(`/api/chores/${encodeURIComponent(c.id)}/done`, { done: c.done, by: S.me }, okText);
  },

  dinner(el) {
    const existing = dinnerOn(el.dataset.day) ||
      (el.dataset.day === S.today ? S.items.find((i) => i.kind === 'dinner' && covers(i, S.today)) : null);
    S.dialog = {
      type: 'dinner', day: el.dataset.day, id: existing ? existing.id : null,
      meal: existing ? existing.title : '', cook: existing ? existing.cook : null,
      time: (existing && existing.time) || DEFAULT_DINNER_TIME,
    };
    render(); focusSoon('meal');
  },
  'dinner-time'(el) { S.dialog.time = el.dataset.value; render(); },
  'dinner-cook'(el) { S.dialog.cook = S.dialog.cook === el.dataset.value ? null : el.dataset.value; render(); },
  'save-dinner'() {
    const d = S.dialog;
    S.dialog = null; render();
    write('/api/dinner', { id: d.id, day: d.day, time: d.time, meal: d.meal.trim(), cook: d.cook });
  },

  'add-chore'() { S.dialog = { type: 'chore', title: '', who: 'Erlina', day: S.today }; render(); focusSoon('chore-title'); },
  'chore-who'(el) { S.dialog.who = el.dataset.value; render(); },
  'chore-day'(el) { S.dialog.day = el.dataset.value; render(); },
  'save-chore'() {
    const d = S.dialog;
    S.dialog = null; render();
    write('/api/chores', { day: d.day, title: d.title.trim(), for: d.who === 'Anyone' ? null : d.who, from: S.me });
  },

  'close-dialog'(el, ev) {
    // A tap on the dim background closes it; a tap inside the dialog doesn't.
    if (el.classList.contains('scrim') && ev.target !== el) return;
    S.dialog = null; render();
  },

  'memo-to'(el) { S.memoTo = el.dataset.value; render(); },
  'send-memo'() {
    const body = S.memoBody.trim();
    if (!body) return;
    S.memoBody = ''; render();
    write('/api/memos', { body, to: S.memoTo, from: S.me }, `Sent to ${S.memoTo === 'Everyone' ? 'everyone' : S.memoTo}`);
  },

  'push-on'() { enablePush(); },
};

function focusSoon(id) {
  setTimeout(() => { const el = document.getElementById(id); if (el) el.focus(); }, 50);
}

document.addEventListener('click', (ev) => {
  const el = ev.target.closest('[data-action]');
  if (el && actions[el.dataset.action]) actions[el.dataset.action](el, ev);
});

document.addEventListener('input', (ev) => {
  const t = ev.target;
  if (t.id === 'memo-body') {
    S.memoBody = t.value;
    const btn = document.querySelector('[data-action="send-memo"]');
    if (btn) btn.disabled = !S.memoBody.trim();
  } else if (S.dialog && (t.id === 'meal' || t.id === 'chore-title')) {
    S.dialog[t.id === 'meal' ? 'meal' : 'title'] = t.value;
    const btn = document.querySelector('.dialog .btn');
    if (btn) btn.disabled = !t.value.trim();
  }
});

document.addEventListener('keydown', (ev) => {
  if (ev.key === 'Escape' && S.dialog) { S.dialog = null; render(); }
  if (ev.key === 'Enter' && S.dialog && ev.target.tagName === 'INPUT') {
    const btn = document.querySelector('.dialog .btn');
    if (btn && !btn.disabled) btn.click();
  }
});

document.addEventListener('submit', async (ev) => {
  if (ev.target.dataset.form !== 'login') return;
  ev.preventDefault();
  const passcode = document.getElementById('passcode').value;
  try {
    const resp = await fetch('/api/login', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ passcode }),
    });
    const data = await resp.json().catch(() => ({}));
    if (!resp.ok) throw new Error(data.error || 'Could not sign in');
    S.token = data.token; store.set('token', S.token); S.loginError = '';
    render(); refresh(); syncPush();
  } catch (e) {
    S.loginError = e.message; render();
  }
});

// ---------- snackbar ----------

let snackTimer = null;
function snack(text) {
  const el = document.getElementById('snackbar');
  el.textContent = text;
  el.classList.add('show');
  clearTimeout(snackTimer);
  snackTimer = setTimeout(() => el.classList.remove('show'), 3500);
}

// ---------- memo alerts (Web Push) ----------

const isIOS = /iPhone|iPad|iPod/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
const standalone = window.matchMedia('(display-mode: standalone)').matches || navigator.standalone === true;

function pushBanner() {
  if (S.push === 'install') {
    return `<div class="memo-banner">${svg('memos')}<div class="body">
      <span class="meta">Get memo alerts</span>
      <span>Tap the Share button, then <b>Add to Home Screen</b>. Open Family Hub from there to turn on alerts.</span>
    </div></div>`;
  }
  if (S.push === 'ask') {
    return `<div class="memo-banner">${svg('memos')}<div class="body">
      <span class="meta">Memo alerts are off</span>
      <span>Get a notification when someone sends you a memo, and a summary at 7am.</span>
      <div class="actions"><button class="btn round" data-action="push-on">Turn on alerts</button></div>
    </div></div>`;
  }
  return '';
}

async function pushRegistration() {
  if (!('serviceWorker' in navigator)) return null;
  return navigator.serviceWorker.ready;
}

// Works out which banner to show, and re-sends an existing subscription so
// the server always knows whose phone it is.
async function syncPush() {
  const supported = 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window;
  if (!supported) S.push = isIOS && !standalone ? 'install' : 'unsupported';
  else if (Notification.permission === 'denied') S.push = 'denied';
  else {
    const reg = await pushRegistration();
    const sub = reg && await reg.pushManager.getSubscription();
    S.push = sub ? 'on' : 'ask';
    if (sub && S.token && S.me) api('/api/push/subscribe', { person: S.me, subscription: sub.toJSON() }).catch(() => {});
  }
  render();
}

async function enablePush() {
  try {
    // Must come straight from the tap, or iOS refuses.
    const perm = await Notification.requestPermission();
    if (perm !== 'granted') { S.push = perm === 'denied' ? 'denied' : 'ask'; render(); return; }
    const reg = await pushRegistration();
    const { key } = await api('/api/push/key');
    const sub = await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: b64ToBytes(key) });
    await api('/api/push/subscribe', { person: S.me, subscription: sub.toJSON() });
    S.push = 'on'; render();
    snack('Memo alerts are on');
  } catch (e) {
    snack(`Couldn't turn on alerts: ${e.message}`);
  }
}

function b64ToBytes(s) {
  const b = atob(s.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - (s.length % 4)) % 4));
  return Uint8Array.from(b, (c) => c.charCodeAt(0));
}

// ---------- start ----------

if ('serviceWorker' in navigator) {
  navigator.serviceWorker.register('/sw.js').catch(() => {});
  navigator.serviceWorker.addEventListener('message', (ev) => {
    if (ev.data && ev.data.open) {
      const tab = new URL(ev.data.open, location.href).searchParams.get('tab');
      if (tab) { S.tab = tab; store.set('tab', tab); }
    }
    refresh();
  });
}

document.addEventListener('visibilitychange', () => { if (!document.hidden) refresh(); });
setInterval(() => { if (!document.hidden && !S.dialog) refresh(); }, REFRESH_MS);

render();
refresh();
syncPush();
