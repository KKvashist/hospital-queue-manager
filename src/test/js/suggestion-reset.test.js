// Bug 1 test: the priority suggestion must never leak from one patient to another.
// Run:  cd src/test/js && npm install jsdom && node --test suggestion-reset.test.js
'use strict';
const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const path = require('path');
const { JSDOM } = require('jsdom');

const STATIC = path.join(__dirname, '../../main/resources/static');

const PATIENTS = [
  { id: 1, name: 'Mild Patient', age: 30, gender: 'F', contact: '1', symptoms: 'cough', registeredAt: null },
  { id: 2, name: 'Chest Patient', age: 50, gender: 'M', contact: '2', symptoms: 'chest pain', registeredAt: null }
];
const SUGGESTIONS = { 1: { level: 'LOW', reason: 'mild' }, 2: { level: 'CRITICAL', reason: 'chest pain' } };

async function startApp() {
  const html = fs.readFileSync(path.join(STATIC, 'index.html'), 'utf8').replace(/<script[^>]*src=[^>]*><\/script>/g, '');
  const dom = new JSDOM(html, { runScripts: 'outside-only', url: 'http://localhost/' });
  const w = dom.window;
  const booked = [];
  w.fetch = async (url, opts = {}) => {
    const reply = (body, status = 200) => ({ ok: status < 400, status, json: async () => body });
    const m = /\/api\/appointments\/suggest\/(\d+)/.exec(url);
    if (m) return reply(SUGGESTIONS[m[1]]);
    if (url === '/api/patients') return reply(PATIENTS);
    if (url === '/api/appointments' && opts.method === 'POST') {
      const b = JSON.parse(opts.body); booked.push(b);
      return reply({ id: 99, finalLevel: b.finalLevel }, 201);
    }
    return reply([]);                                  // every other GET: empty list
  };
  w.setInterval = () => 0;
  w.eval(fs.readFileSync(path.join(STATIC, 'js/app.js'), 'utf8'));
  w.document.querySelector('#nav button[data-view="appointments"]').click();
  await new Promise(r => setTimeout(r, 30));
  return { w, booked, doc: w.document };
}
const tick = () => new Promise(r => setTimeout(r, 30));
const choose = (w, id) => { const s = w.document.getElementById('apPatient'); s.value = String(id); s.dispatchEvent(new w.Event('change')); };

test('changing the patient hides and clears the old suggestion', async () => {
  const { w, doc } = await startApp();
  doc.getElementById('apSuggest').click(); await tick();
  assert.equal(doc.getElementById('reviewBox').hidden, false);
  assert.match(doc.getElementById('suggestBadge').textContent, /LOW/);

  choose(w, 2);

  assert.equal(doc.getElementById('reviewBox').hidden, true);
  assert.equal(doc.getElementById('suggestBadge').textContent, '');
});

test('Confirm after switching patient does NOT book with the previous patient\'s priority', async () => {
  const { w, booked, doc } = await startApp();
  doc.getElementById('apSuggest').click(); await tick();      // suggestion for patient 1 (LOW)
  doc.getElementById('apReviewer').value = 'Nurse Joy';
  choose(w, 2);                                                // switch to patient 2 without asking again

  doc.getElementById('apConfirm').click(); await tick();

  assert.equal(booked.length, 0, 'nothing may be booked with a stale suggestion');
});

test('suggestion fetched again for the new patient books the correct priority', async () => {
  const { w, booked, doc } = await startApp();
  doc.getElementById('apSuggest').click(); await tick();
  choose(w, 2);
  doc.getElementById('apSuggest').click(); await tick();       // now CRITICAL for patient 2
  doc.getElementById('apReviewer').value = 'Nurse Joy';

  doc.getElementById('apConfirm').click(); await tick();

  assert.equal(booked.length, 1);
  assert.equal(booked[0].patientId, 2);
  assert.equal(booked[0].suggestedLevel, 'CRITICAL');
  assert.equal(booked[0].finalLevel, 'CRITICAL');
});

test('a late suggestion response for the previously selected patient is ignored', async () => {
  const { w, doc } = await startApp();
  const originalFetch = w.fetch;
  w.fetch = (url, o) => /suggest\/1/.test(url) ? new Promise(r => setTimeout(() => r(originalFetch(url, o)), 60)) : originalFetch(url, o);
  doc.getElementById('apSuggest').click();                     // slow request for patient 1
  choose(w, 2);                                                // user switches before it returns
  await new Promise(r => setTimeout(r, 120));

  assert.equal(doc.getElementById('reviewBox').hidden, true);
});
