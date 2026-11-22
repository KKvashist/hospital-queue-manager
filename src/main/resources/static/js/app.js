'use strict';

// ---------- helpers ----------
const $ = id => document.getElementById(id);
const esc = s => String(s ?? '').replace(/[&<>"']/g,
  c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));   // user text is never trusted
const fmt = iso => iso ? new Date(iso).toLocaleString() : '-';
const levelBadge = l => `<span class="badge ${esc(l)}">${esc(l)}</span>`;
const statusTag = s => `<span class="status ${esc(s)}">${esc(s.replace('_', ' '))}</span>`;

async function api(url, options = {}) {
  const res = await fetch(url, { headers: { 'Content-Type': 'application/json' }, ...options });
  if (res.status === 204) return null;
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(body.error || `Request failed (${res.status})`);
  return body;
}
const post = (url, data) => api(url, { method: 'POST', body: JSON.stringify(data) });

let toastTimer;
function toast(message, isError = false) {
  const t = $('toast');
  t.textContent = message; t.className = 'toast' + (isError ? ' error' : ''); t.hidden = false;
  clearTimeout(toastTimer); toastTimer = setTimeout(() => { t.hidden = true; }, 4000);
}
async function guard(fn) { try { await fn(); } catch (e) { toast(e.message, true); } }

function table(el, headers, rows) {
  el.innerHTML = '<thead><tr>' + headers.map(h => `<th>${esc(h)}</th>`).join('') + '</tr></thead><tbody>'
    + (rows.length ? rows.join('') : `<tr><td colspan="${headers.length}" class="muted">Nothing to show</td></tr>`) + '</tbody>';
}

// ---------- navigation ----------
let currentView = 'dashboard';
const loaders = {
  dashboard: loadDashboard, patients: () => loadPatients(), doctors: loadDoctors, appointments: loadAppointments,
  queue: loadQueue, workload: loadWorkload, history: () => loadHistory()
};
function showView(name) {
  currentView = name;
  document.querySelectorAll('.view').forEach(v => { v.hidden = v.id !== 'view-' + name; });
  document.querySelectorAll('#nav button').forEach(b => b.classList.toggle('active', b.dataset.view === name));
  guard(loaders[name]);
}
$('nav').addEventListener('click', e => { if (e.target.dataset.view) showView(e.target.dataset.view); });
const refresh = () => guard(loaders[currentView]);
setInterval(refresh, 5000);   // keeps the dashboard and queue live

// ---------- dashboard ----------
const LEVELS = [['CRITICAL', '#d64545'], ['HIGH', '#e08a00'], ['MEDIUM', '#c9a400'], ['LOW', '#2e9e6b']];

function renderDonut(appointments) {
  const counts = Object.fromEntries(LEVELS.map(([l]) => [l, 0]));
  appointments.forEach(a => { if (a.finalLevel in counts) counts[a.finalLevel]++; });
  const total = appointments.length;
  let offset = 25, rings = '';                     // r = 15.9155 makes the circumference exactly 100
  if (total === 0) {
    rings = '<circle cx="21" cy="21" r="15.9155" fill="none" stroke="#e6edf3" stroke-width="5"/>';
  } else {
    LEVELS.forEach(([l, color]) => {
      const pct = counts[l] * 100 / total;
      if (pct > 0) {
        rings += `<circle cx="21" cy="21" r="15.9155" fill="none" stroke="${color}" stroke-width="5"
          stroke-dasharray="${pct} ${100 - pct}" stroke-dashoffset="${offset}"/>`;
        offset -= pct;
      }
    });
  }
  $('donut').innerHTML = `<svg viewBox="0 0 42 42" role="img" aria-label="Appointments by priority">${rings}
    <text x="21" y="21.5" text-anchor="middle" class="donut-n">${total}</text>
    <text x="21" y="26.5" text-anchor="middle" class="donut-l">appointments</text></svg>`;
  $('donutLegend').innerHTML = LEVELS.map(([l, color]) => {
    const pct = total ? Math.round(counts[l] * 100 / total) : 0;
    return `<li><span class="dot" style="background:${color}"></span><span class="lg-name">${l}</span>
      <span class="lg-n">${counts[l]}</span><span class="lg-pct">${pct}%</span></li>`;
  }).join('');
}

async function loadDashboard() {
  const [s, all] = await Promise.all([api('/api/dashboard'), api('/api/appointments')]);
  const card = (n, l, cls = '') => `<div class="card ${cls}"><div class="n">${n}</div><div class="l">${l}</div></div>`;
  $('statCards').innerHTML = card(s.waiting, 'Waiting', 'hero') + card(s.inProgress, 'In consultation')
    + card(s.treated, 'Treated') + card(s.availableDoctors, 'Free doctors')
    + card(s.emergencyPatients, 'Emergency (Critical/High)', 'alert')
    + card(s.totalPatients, 'Registered patients') + card(s.totalAppointments, 'Total appointments');
  renderDonut(all);
  const t = s.highestPriorityPending;
  $('topCase').innerHTML = t
    ? `<div class="tc-name">#${t.id} ${esc(t.patientName)} ${levelBadge(t.finalLevel)}</div>
       <div class="muted">Requested ${fmt(t.requestTime)} &middot; reviewed by ${esc(t.reviewedBy)}</div>`
    : '<div class="tc-empty">No patients are waiting.</div>';
  $('updated').textContent = 'Updated ' + new Date().toLocaleTimeString();
}
$('simRun').addEventListener('click', () => guard(async () => {
  $('simRun').disabled = true; $('simResult').textContent = 'Running...';
  try {
    const r = await post('/api/simulation/run',
      { desks: +$('simDesks').value, doctors: +$('simDoctors').value, appointments: +$('simTotal').value });
    $('simResult').innerHTML = `<b>Done in ${r.millis} ms.</b> Submitted ${r.submitted}, processed ${r.processed}, `
      + `duplicate assignments: <b>${r.duplicateAssignments}</b>`;
    toast('Simulation finished'); await loadDashboard();
  } catch (e) { $('simResult').textContent = ''; throw e; }
  finally { $('simRun').disabled = false; }
}));

// ---------- patients ----------
async function loadPatients(q = $('patientSearch').value.trim()) {
  const list = await api('/api/patients' + (q ? '?q=' + encodeURIComponent(q) : ''));
  table($('patientTable'), ['ID', 'Name', 'Age', 'Gender', 'Contact', 'Symptoms', 'Registered'],
    list.map(p => `<tr><td>P-${String(p.id).padStart(5, '0')} (${p.id})</td><td>${esc(p.name)}</td><td>${p.age}</td>`
      + `<td>${esc(p.gender)}</td><td>${esc(p.contact)}</td><td>${esc(p.symptoms)}</td><td>${fmt(p.registeredAt)}</td></tr>`));
}
$('patientForm').addEventListener('submit', e => { e.preventDefault(); guard(async () => {
  const f = new FormData(e.target);
  const p = await post('/api/patients', { name: f.get('name'), age: +f.get('age'), gender: f.get('gender'),
    contact: f.get('contact'), symptoms: f.get('symptoms') });
  toast(`Patient registered with ID ${p.id}`); e.target.reset(); await loadPatients();
}); });
$('patientSearchBtn').addEventListener('click', () => guard(() => loadPatients()));
$('patientSearch').addEventListener('keydown', e => { if (e.key === 'Enter') guard(() => loadPatients()); });

// ---------- doctors ----------
async function loadDoctors() {
  const [byDept, workload] = await Promise.all([api('/api/doctors/by-department'), api('/api/dashboard')]);
  const load = Object.fromEntries(workload.workload.map(w => [w.doctorId, w]));
  const depts = Object.keys(byDept).sort();
  $('doctorGroups').innerHTML = depts.length ? depts.map(d => `<h2 class="group-title">${esc(d)}</h2>
    <div class="panel"><div class="table-wrap"><table><thead><tr><th>ID</th><th>Name</th><th>Specialization</th>
    <th>Status</th><th>Treated</th><th></th></tr></thead><tbody>`
    + byDept[d].map(doc => { const w = load[doc.id] || { inProgress: 0, treated: 0 };
      const state = !doc.available ? 'Off duty' : (w.inProgress > 0 ? 'Busy' : 'Available');
      return `<tr><td>${doc.id}</td><td>${esc(doc.name)}</td><td>${esc(doc.specialization)}</td><td>${state}</td>
        <td>${w.treated}</td><td><button class="btn small secondary" data-toggle="${doc.id}" data-to="${!doc.available}">
        ${doc.available ? 'Set off duty' : 'Set on duty'}</button></td></tr>`; }).join('')
    + '</tbody></table></div></div>').join('') : '<div class="panel muted">No doctors yet. Add one above.</div>';
}
$('doctorForm').addEventListener('submit', e => { e.preventDefault(); guard(async () => {
  const f = new FormData(e.target);
  await post('/api/doctors', { name: f.get('name'), specialization: f.get('specialization'), department: f.get('department') });
  toast('Doctor added'); e.target.reset(); await loadDoctors();
}); });
$('doctorGroups').addEventListener('click', e => { const id = e.target.dataset.toggle; if (!id) return;
  guard(async () => { await api(`/api/doctors/${id}/availability`, { method: 'PUT',
    body: JSON.stringify({ available: e.target.dataset.to === 'true' }) }); await loadDoctors(); }); });

// ---------- appointments (with nurse/doctor verification) ----------
let suggested = null;
let suggestedFor = null;   // id of the patient the current suggestion belongs to
function resetSuggestion() {
  suggested = null; suggestedFor = null;
  $('reviewBox').hidden = true; $('suggestBadge').innerHTML = ''; $('suggestReason').textContent = '';
}
async function loadAppointments() {
  const patients = await api('/api/patients');
  const keep = $('apPatient').value;
  $('apPatient').innerHTML = patients.map(p => `<option value="${p.id}">#${p.id} ${esc(p.name)}</option>`).join('');
  if (keep) $('apPatient').value = keep;
  const status = $('apFilter').value;
  const list = await api('/api/appointments' + (status ? '?status=' + status : ''));
  table($('apTable'), ['ID', 'Patient', 'Priority', 'Suggested', 'Reviewed by', 'Status', 'Doctor', 'Requested'],
    list.map(a => `<tr class="${a.finalLevel === 'CRITICAL' && a.status === 'WAITING' ? 'critical' : ''}"><td>${a.id}</td>
      <td>${esc(a.patientName)}</td><td>${levelBadge(a.finalLevel)}</td>
      <td>${levelBadge(a.suggestedLevel)}${a.suggestedLevel !== a.finalLevel ? ' <span class="muted">overridden</span>' : ''}</td>
      <td>${esc(a.reviewedBy)}</td><td>${statusTag(a.status)}</td><td>${esc(a.doctorName || '-')}</td><td>${fmt(a.requestTime)}</td></tr>`));
}
$('apFilter').addEventListener('change', refresh);
$('apPatient').addEventListener('change', resetSuggestion);   // another patient chosen: the old suggestion no longer applies
$('apSuggest').addEventListener('click', () => guard(async () => {
  const id = $('apPatient').value; if (!id) throw new Error('Register a patient first');
  const result = await api('/api/appointments/suggest/' + id);
  if ($('apPatient').value !== id) return;           // patient was changed while the request was running: ignore the late answer
  suggested = result; suggestedFor = id;
  $('suggestBadge').innerHTML = levelBadge(suggested.level); $('suggestReason').textContent = suggested.reason;
  $('apFinal').value = suggested.level; $('reviewBox').hidden = false;
}));
async function bookAppointment(finalLevel) {
  const patientId = $('apPatient').value;
  if (!suggested || suggestedFor !== patientId) {      // safety net: never book with another patient's suggestion
    resetSuggestion();
    throw new Error('Get the priority suggestion for this patient first');
  }
  const a = await post('/api/appointments', { patientId: +patientId, suggestedLevel: suggested.level,
    finalLevel, reviewedBy: $('apReviewer').value });
  toast(`Appointment #${a.id} created with priority ${a.finalLevel}`);
  resetSuggestion(); await loadAppointments();
}
$('apConfirm').addEventListener('click', () => guard(() => bookAppointment(suggested && suggested.level)));
$('apOverride').addEventListener('click', () => guard(() => bookAppointment($('apFinal').value)));

// ---------- queue ----------
async function loadQueue() {
  const [queue, inProgress, doctors] = await Promise.all(
    [api('/api/appointments/queue'), api('/api/appointments?status=IN_PROGRESS'), api('/api/doctors')]);
  const keep = $('qDoctor').value;
  $('qDoctor').innerHTML = doctors.filter(d => d.available).map(d => `<option value="${d.id}">${esc(d.name)} (${esc(d.department)})</option>`).join('');
  if (keep) $('qDoctor').value = keep;
  table($('queueTable'), ['#', 'Appt', 'Patient', 'Priority', 'Requested', 'Reviewed by'],
    queue.map((a, i) => `<tr class="${a.finalLevel === 'CRITICAL' ? 'critical' : ''}"><td>${i + 1}</td><td>${a.id}</td>
      <td>${esc(a.patientName)}</td><td>${levelBadge(a.finalLevel)}</td><td>${fmt(a.requestTime)}</td><td>${esc(a.reviewedBy)}</td></tr>`));
  table($('progressTable'), ['Appt', 'Patient', 'Priority', 'Doctor', ''],
    inProgress.map(a => `<tr><td>${a.id}</td><td>${esc(a.patientName)}</td><td>${levelBadge(a.finalLevel)}</td>
      <td>${esc(a.doctorName)}</td><td><button class="btn small" data-complete="${a.id}" data-info="${esc(a.patientName)}">Complete consultation</button></td></tr>`));
}
$('callNext').addEventListener('click', () => guard(async () => {
  const id = $('qDoctor').value; if (!id) throw new Error('Add an available doctor first');
  const a = await post('/api/appointments/call-next', { doctorId: +id });
  toast(a ? `Called #${a.id} ${a.patientName} (${a.finalLevel})` : 'No patients are waiting');
  await loadQueue();
}));
$('progressTable').addEventListener('click', e => { const id = e.target.dataset.complete; if (!id) return;
  $('completeInfo').textContent = `Appointment #${id} - ${e.target.dataset.info}`;
  $('completeDialog').dataset.id = id; $('cDiagnosis').value = ''; $('cNotes').value = '';
  $('completeDialog').showModal(); });
$('cSave').addEventListener('click', () => guard(async () => {
  const d = $('completeDialog');
  await post(`/api/appointments/${d.dataset.id}/complete`, { diagnosis: $('cDiagnosis').value, notes: $('cNotes').value });
  d.close(); toast('Consultation completed and saved'); await loadQueue();
}));

// ---------- workload + history ----------
async function loadWorkload() {
  const s = await api('/api/dashboard');
  table($('workloadTable'), ['Doctor', 'Department', 'Status', 'In consultation', 'Treated'],
    s.workload.map(w => `<tr><td>${esc(w.name)}</td><td>${esc(w.department)}</td>
      <td>${!w.available ? 'Off duty' : (w.inProgress > 0 ? 'Busy' : 'Available')}</td><td>${w.inProgress}</td><td>${w.treated}</td></tr>`));
}
async function loadHistory() {
  const pid = $('historyPatient').value;
  const list = await api('/api/consultations' + (pid ? '?patientId=' + encodeURIComponent(pid) : ''));
  table($('historyTable'), ['ID', 'Appt', 'Patient', 'Doctor', 'Diagnosis', 'Notes', 'When'],
    list.map(c => `<tr><td>${c.id}</td><td>${c.appointmentId}</td><td>${esc(c.patientName)}</td><td>${esc(c.doctorName)}</td>
      <td>${esc(c.diagnosis)}</td><td>${esc(c.notes)}</td><td>${fmt(c.consultedAt)}</td></tr>`));
}
$('historyBtn').addEventListener('click', refresh);

showView('dashboard');
