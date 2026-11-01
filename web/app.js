/* Campus Connect - front-end logic (vanilla JavaScript) */

const $ = (s) => document.querySelector(s);
const esc = (s) => String(s ?? '').replace(/[&<>"']/g,
  (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

const STATUS_LABEL = { PENDING: 'Pending', IN_PROGRESS: 'In progress', RESOLVED: 'Resolved', REJECTED: 'Rejected' };
const PRIORITY_LABEL = { LOW: 'Low', MEDIUM: 'Medium', HIGH: 'High' };

let user = null;
let currentView = 'dashboard';
let categories = [];
let notifTimer = null;

/* ---------- helpers ---------- */
async function api(path, method = 'GET', data) {
  const opts = { method, credentials: 'same-origin' };
  if (data) {
    opts.headers = { 'Content-Type': 'application/x-www-form-urlencoded' };
    opts.body = new URLSearchParams(data).toString();
  }
  const res = await fetch('/api/' + path, opts);
  const json = await res.json().catch(() => ({}));
  if (!res.ok) {
    const err = new Error(json.error || 'Something went wrong.');
    err.status = res.status;
    throw err;
  }
  return json;
}

function toast(msg, isError = false) {
  const t = $('#toast');
  t.textContent = msg;
  t.className = 'toast show' + (isError ? ' error' : '');
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => (t.className = 'toast'), 3200);
}

const badge = (s) => `<span class="badge b-${esc(s)}">${esc(STATUS_LABEL[s] || s)}</span>`;
const prio = (p) => `<span class="p-${esc(p)}">${esc(PRIORITY_LABEL[p] || p)}</span>`;
const isAdmin = () => user && user.role === 'ADMIN';

function fmtDate(s) {
  if (!s) return '';
  const d = new Date(s.replace(' ', 'T') + 'Z');
  return d.toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' });
}

/* ---------- auth screen ---------- */
function showTab(which) {
  $('#login-form').classList.toggle('hidden', which !== 'login');
  $('#register-form').classList.toggle('hidden', which !== 'register');
  $('#tab-login').classList.toggle('active', which === 'login');
  $('#tab-register').classList.toggle('active', which === 'register');
}
$('#tab-login').onclick = () => showTab('login');
$('#tab-register').onclick = () => showTab('register');

$('#login-form').onsubmit = async (e) => {
  e.preventDefault();
  const btn = e.submitter; btn.disabled = true;
  try {
    user = await api('login', 'POST', Object.fromEntries(new FormData(e.target)));
    $('#login-error').textContent = '';
    e.target.reset();
    startApp();
  } catch (err) { $('#login-error').textContent = err.message; }
  btn.disabled = false;
};

$('#register-form').onsubmit = async (e) => {
  e.preventDefault();
  const btn = e.submitter; btn.disabled = true;
  const form = Object.fromEntries(new FormData(e.target));
  try {
    await api('register', 'POST', form);
    user = await api('login', 'POST', { email: form.email, password: form.password });
    $('#register-error').textContent = '';
    e.target.reset();
    toast('Account created. Welcome to Campus Connect!');
    startApp();
  } catch (err) { $('#register-error').textContent = err.message; }
  btn.disabled = false;
};

$('#logout').onclick = async () => {
  await api('logout', 'POST').catch(() => {});
  user = null;
  clearInterval(notifTimer);
  $('#app').classList.add('hidden');
  $('#auth').classList.remove('hidden');
  showTab('login');
};

/* ---------- app shell ---------- */
async function startApp() {
  $('#auth').classList.add('hidden');
  $('#app').classList.remove('hidden');
  $('#who').textContent = user.name + (isAdmin() ? ' (Admin)' : '');
  categories = await api('categories');

  const items = isAdmin()
    ? [['dashboard', 'Overview'], ['complaints', 'All complaints'], ['reports', 'Reports']]
    : [['dashboard', 'Overview'], ['new', 'New complaint'], ['complaints', 'My complaints']];
  $('#nav').innerHTML = items.map(([k, label]) => `<button data-view="${k}">${label}</button>`).join('');
  $('#nav').onclick = (e) => { if (e.target.dataset.view) go(e.target.dataset.view); };

  go('dashboard');
  loadNotifications();
  clearInterval(notifTimer);
  notifTimer = setInterval(loadNotifications, 30000);
}

function go(view) {
  currentView = view;
  document.querySelectorAll('#nav button').forEach((b) => b.classList.toggle('active', b.dataset.view === view));
  const render = { dashboard: viewDashboard, new: viewNew, complaints: viewComplaints, reports: viewReports }[view];
  render().catch((err) => {
    if (err.status === 401) return location.reload();
    $('#view').innerHTML = `<div class="card empty"><h3>Couldn't load this page</h3>${esc(err.message)}</div>`;
  });
}

/* ---------- dashboard ---------- */
async function viewDashboard() {
  const list = await api('complaints');
  const count = (s) => list.filter((c) => c.status === s).length;
  const greeting = isAdmin() ? 'Here is what needs attention across campus.' : 'Track the complaints you have raised.';

  $('#view').innerHTML = `
    <div class="page-head">
      <div><h2>Hello, ${esc(user.name.split(' ')[0])}</h2><p>${greeting}</p></div>
      ${isAdmin() ? '' : '<button class="btn primary" onclick="go(\'new\')">Raise a complaint</button>'}
    </div>
    <div class="stats">
      <div class="stat s-TOTAL"><b>${list.length}</b><span>Total complaints</span></div>
      <div class="stat s-PENDING"><b>${count('PENDING')}</b><span>Waiting for review</span></div>
      <div class="stat s-IN_PROGRESS"><b>${count('IN_PROGRESS')}</b><span>Being handled</span></div>
      <div class="stat s-RESOLVED"><b>${count('RESOLVED')}</b><span>Resolved</span></div>
    </div>
    <h3 style="margin-bottom:12px">Recent activity</h3>
    ${complaintTable(list.slice(0, 5))}`;
  bindRows();
}

/* ---------- complaint list ---------- */
function complaintTable(list) {
  if (!list.length) {
    return `<div class="card empty"><h3>No complaints yet</h3>${isAdmin()
      ? 'New complaints from students and staff will appear here.'
      : 'When something needs fixing, raise a complaint and it will show up here.'}</div>`;
  }
  return `<div class="table-wrap"><table>
    <thead><tr><th>#</th><th>Complaint</th><th>Category</th><th>Priority</th><th>Status</th><th>Submitted</th></tr></thead>
    <tbody>${list.map((c) => `
      <tr data-id="${c.id}" tabindex="0">
        <td>${c.id}</td>
        <td><div class="title">${esc(c.title)}</div>${isAdmin() ? `<small>by ${esc(c.user_name)}</small>` : ''}</td>
        <td>${esc(c.category)}</td>
        <td>${prio(c.priority)}</td>
        <td>${badge(c.status)}</td>
        <td><small>${fmtDate(c.created_at)}</small></td>
      </tr>`).join('')}</tbody></table></div>`;
}

function bindRows() {
  document.querySelectorAll('tbody tr[data-id]').forEach((tr) => {
    tr.onclick = () => openComplaint(tr.dataset.id);
    tr.onkeydown = (e) => { if (e.key === 'Enter') openComplaint(tr.dataset.id); };
  });
}

async function viewComplaints() {
  $('#view').innerHTML = `
    <div class="page-head"><div>
      <h2>${isAdmin() ? 'All complaints' : 'My complaints'}</h2>
      <p>${isAdmin() ? 'Open a complaint to update its status.' : 'Select a complaint to see its full history.'}</p></div></div>
    <div class="filters">
      <input id="f-q" type="search" placeholder="Search by title, details${isAdmin() ? ' or name' : ''}">
      <select id="f-status">
        <option value="">All statuses</option>
        ${Object.entries(STATUS_LABEL).map(([k, v]) => `<option value="${k}">${v}</option>`).join('')}
      </select>
    </div>
    <div id="list"></div>`;

  let timer;
  const load = async () => {
    const q = encodeURIComponent($('#f-q').value.trim());
    const list = await api(`complaints?status=${$('#f-status').value}&q=${q}`);
    $('#list').innerHTML = complaintTable(list);
    bindRows();
  };
  $('#f-q').oninput = () => { clearTimeout(timer); timer = setTimeout(load, 250); };
  $('#f-status').onchange = load;
  await load();
}

/* ---------- new complaint ---------- */
async function viewNew() {
  $('#view').innerHTML = `
    <div class="page-head"><div><h2>Raise a complaint</h2><p>Be specific. Location and time help us fix it faster.</p></div></div>
    <form id="new-form" class="card form narrow">
      <label>Title <input name="title" required maxlength="150" placeholder="e.g. Water leakage in Hostel Block B, 2nd floor"></label>
      <div class="row">
        <label>Category
          <select name="category_id" required>
            <option value="">Select a category</option>
            ${categories.map((c) => `<option value="${c.id}">${esc(c.name)}</option>`).join('')}
          </select>
        </label>
        <label>Priority
          <select name="priority">
            <option value="LOW">Low</option><option value="MEDIUM" selected>Medium</option><option value="HIGH">High</option>
          </select>
        </label>
      </div>
      <label>What happened? <textarea name="description" required minlength="10" maxlength="2000"></textarea></label>
      <p class="form-error" id="new-error"></p>
      <div><button class="btn primary" type="submit">Submit complaint</button></div>
    </form>`;

  $('#new-form').onsubmit = async (e) => {
    e.preventDefault();
    const btn = e.submitter; btn.disabled = true;
    try {
      const res = await api('complaints', 'POST', Object.fromEntries(new FormData(e.target)));
      toast(`Complaint #${res.id} submitted`);
      go('complaints');
    } catch (err) { $('#new-error').textContent = err.message; btn.disabled = false; }
  };
}

/* ---------- complaint detail (modal) ---------- */
async function openComplaint(id) {
  const c = await api('complaints/' + id).catch((err) => { toast(err.message, true); });
  if (!c) return;
  const modal = $('#modal');
  modal.innerHTML = `
    <div class="modal-box">
      <div class="modal-head">
        <div><h3>#${c.id} ${esc(c.title)}</h3></div>
        <button class="close" aria-label="Close">&times;</button>
      </div>
      <div class="meta">
        ${badge(c.status)}
        <span>${esc(c.category)}</span>
        <span>Priority: ${prio(c.priority)}</span>
        <span>By ${esc(c.user_name)}</span>
        <span>${fmtDate(c.created_at)}</span>
      </div>
      <p class="desc">${esc(c.description)}</p>
      <h3 style="font-size:15px;margin-bottom:12px">Progress</h3>
      <ul class="timeline">
        ${c.history.map((h) => `<li>${badge(h.status)} <small>${fmtDate(h.changed_at)} by ${esc(h.changed_by)}</small>
          ${h.remarks ? `<div>${esc(h.remarks)}</div>` : ''}</li>`).join('')}
      </ul>
      ${isAdmin() ? `
        <form id="status-form" class="admin-box">
          <label>Update status
            <select name="status">
              ${Object.entries(STATUS_LABEL).map(([k, v]) => `<option value="${k}" ${k === c.status ? 'selected' : ''}>${v}</option>`).join('')}
            </select>
          </label>
          <label>Remarks for the complainant <textarea name="remarks" maxlength="500" placeholder="What was done, or what happens next?"></textarea></label>
          <div><button class="btn primary" type="submit">Save update</button></div>
        </form>` : ''}
    </div>`;
  modal.classList.remove('hidden');

  const close = () => modal.classList.add('hidden');
  modal.querySelector('.close').onclick = close;
  modal.onclick = (e) => { if (e.target === modal) close(); };

  const form = $('#status-form');
  if (form) {
    form.onsubmit = async (e) => {
      e.preventDefault();
      try {
        await api(`complaints/${c.id}/status`, 'POST', Object.fromEntries(new FormData(form)));
        toast(`Complaint #${c.id} updated`);
        close();
        go(currentView);
        loadNotifications();
      } catch (err) { toast(err.message, true); }
    };
  }
}
document.addEventListener('keydown', (e) => { if (e.key === 'Escape') $('#modal').classList.add('hidden'); });

/* ---------- reports (admin) ---------- */
async function viewReports() {
  const r = await api('reports');
  const bars = (rows, labelFn, cls) => {
    const max = Math.max(1, ...rows.map((x) => x.cnt));
    return rows.length ? rows.map((x) => `
      <div class="bar-row"><span>${esc(labelFn(x.label))}</span>
        <div class="bar"><i class="${cls ? 'c-' + esc(x.label) : ''}" style="width:${(x.cnt / max) * 100}%"></i></div>
        <b>${x.cnt}</b></div>`).join('') : '<p class="hint">No data yet.</p>';
  };
  $('#view').innerHTML = `
    <div class="page-head">
      <div><h2>Reports</h2><p>${r.total} complaints in total${r.avgResolutionHours != null ? `, resolved in ${r.avgResolutionHours} hours on average` : ''}.</p></div>
      <a class="btn primary" href="/api/reports/csv" download>Download CSV</a>
    </div>
    <div class="grid2">
      <div class="card"><h3>By status</h3>${bars(r.byStatus, (l) => STATUS_LABEL[l] || l, true)}</div>
      <div class="card"><h3>By priority</h3>${bars(r.byPriority, (l) => PRIORITY_LABEL[l] || l)}</div>
    </div>
    <div class="card" style="margin-top:16px"><h3>By category</h3>${bars(r.byCategory, (l) => l)}</div>`;
}

/* ---------- notifications ---------- */
async function loadNotifications() {
  try {
    const n = await api('notifications');
    const c = $('#bell-count');
    c.textContent = n.unread;
    c.classList.toggle('hidden', n.unread === 0);
    $('#notif-panel').innerHTML = '<h3>Notifications</h3>' + (n.items.length
      ? n.items.map((i) => `<div class="notif-item ${i.is_read ? '' : 'unread'}">${esc(i.message)}<small>${fmtDate(i.created_at)}</small></div>`).join('')
      : '<div class="notif-empty">You are all caught up.</div>');
  } catch (e) { /* session may have expired; ignore */ }
}

$('#bell').onclick = async (e) => {
  e.stopPropagation();
  const panel = $('#notif-panel');
  panel.classList.toggle('hidden');
  if (!panel.classList.contains('hidden')) {
    await api('notifications/read', 'POST').catch(() => {});
    $('#bell-count').classList.add('hidden');
  }
};
document.addEventListener('click', (e) => {
  if (!e.target.closest('.bell-wrap')) $('#notif-panel').classList.add('hidden');
});

/* ---------- boot ---------- */
(async function init() {
  try { user = await api('me'); startApp(); }
  catch { $('#auth').classList.remove('hidden'); }
})();
