const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const source = fs.readFileSync(path.resolve(__dirname, '../../main/webapp/assets/js/defects.js'), 'utf8');
const flush = () => new Promise(resolve => setImmediate(resolve));
const plain = value => JSON.parse(JSON.stringify(value));

function browser(context = { view: 'defects', projectId: '1' }) {
  const nodes = new Map(), calls = [], listeners = new Map(), emitted = [], opened = [];
  let route = { ...context };
  class Element {
    constructor(tag = 'div', attrs = {}) {
      this.tag = tag; this.attrs = attrs; this.children = []; this.parent = null;
      this.classes = new Set((attrs.class || '').split(/\s+/).filter(Boolean));
      this.handlers = new Map(); this.content = ''; this.value = ''; this.disabled = false;
      this.required = false;
      if (attrs.id) nodes.set(attrs.id, this);
    }
    all() { return this.children.flatMap(child => [child, ...child.all()]); }
    reset() { this.all().forEach(node => { node.value = node.tag === 'select' ? node.children[0]?.value || '' : ''; }); }
    reportValidity() { return this.all().every(node => !node.required || node.disabled || !!node.value); }
  }
  function matches(node, selector) {
    const match = /^(?:#([\w-]+)|([\w-]+))?(?:\[value="([^"]*)"\])?$/.exec(selector);
    return !!match && (!match[1] || node.attrs.id === match[1]) && (!match[2] || node.tag === match[2]) &&
      (match[3] === undefined || String(node.value) === match[3]);
  }
  function query(selector) {
    return selector.split(',').flatMap(part => {
      const pieces = part.trim().split(/\s+/);
      let values = [...nodes.values()].filter(node => matches(node, pieces[0]));
      for (const piece of pieces.slice(1)) values = values.flatMap(node => node.all().filter(child => matches(child, piece)));
      return values;
    });
  }
  class Collection {
    constructor(values) { this.values = [...new Set(values)]; this.length = this.values.length; this.values.forEach((value, index) => { this[index] = value; }); }
    each(fn) { this.values.forEach(node => fn(node)); return this; }
    on(name, fn) { return this.each(node => { if (!node.handlers.has(name)) node.handlers.set(name, []); node.handlers.get(name).push(fn); }); }
    text(value) {
      if (value === undefined) return this.values.map(node => node.content + node.all().map(child => child.content).join('')).join('');
      return this.each(node => { node.content = String(value); node.children = []; });
    }
    val(value) {
      if (value === undefined) return this[0]?.value ?? null;
      return this.each(node => { node.value = String(value ?? ''); });
    }
    empty() { return this.each(node => { node.children = []; node.content = ''; if (node.tag === 'select') node.value = ''; }); }
    append(...items) { return this.each(node => items.forEach(item => (item instanceof Collection ? item.values : [item]).forEach(child => { child.parent = node; node.children.push(child); }))); }
    prop(name, value) { if (value === undefined) return this[0]?.[name]; return this.each(node => { node[name] = value; }); }
    attr(name, value) { return this.each(node => { node.attrs[name] = String(value); if (name === 'id') nodes.set(String(value), node); }); }
    toggleClass(name, value) { return this.each(node => { if (value) node.classes.add(name); else node.classes.delete(name); }); }
    addClass(value) { return this.each(node => value.split(/\s+/).forEach(name => node.classes.add(name))); }
    removeClass(name) { return this.each(node => node.classes.delete(name)); }
    hasClass(name) { return this[0]?.classes.has(name) || false; }
    find(selector) { return new Collection(this.values.flatMap(node => node.all().filter(child => matches(child, selector)))); }
    after(item) { return this.each(node => { item.values.forEach(child => { child.parent = node.parent; node.parent.children.splice(node.parent.children.indexOf(node) + 1, 0, child); }); }); }
    remove() { return this.each(node => { if (node.parent) node.parent.children.splice(node.parent.children.indexOf(node), 1); if (node.attrs.id) nodes.delete(node.attrs.id); }); }
    trigger(name) { if (name === 'focus' && this[0]) document.activeElement = this[0]; return this; }
  }
  const document = {
    handlers: new Map(),
    addEventListener(name, fn) { if (!listeners.has(name)) listeners.set(name, []); listeners.get(name).push(fn); },
    dispatchEvent(event) {
      emitted.push(event);
      (document.handlers.get(event.type) || []).forEach(fn => fn({ originalEvent: event }));
      (listeners.get(event.type) || []).forEach(fn => fn(event));
    }
  };
  function $(selector) {
    if (selector === document) return new Collection([document]);
    if (typeof selector !== 'string') return new Collection([selector]);
    if (selector.startsWith('<')) {
      const attrs = Object.fromEntries([...selector.matchAll(/([\w-]+)="([^"]*)"/g)].map(match => [match[1], match[2]]));
      return new Collection([new Element(/^<([\w-]+)/.exec(selector)[1], attrs)]);
    }
    return new Collection(query(selector));
  }
  function add(id, tag = 'div', parent) {
    const node = new Element(tag, { id });
    if (parent) { node.parent = nodes.get(parent); node.parent.children.push(node); }
    return node;
  }
  ['defect-list-panel', 'defect-detail-panel', 'defect-create-form'].forEach(id => add(id, id.endsWith('form') ? 'form' : 'div'));
  ['defect-notice', 'defect-list-status', 'defect-list', 'defect-new'].forEach(id => add(id));
  add('defect-list-heading', 'h2', 'defect-list-panel');
  ['defect-detail-title', 'defect-detail', 'defect-evidence', 'defect-evidence-status', 'defect-actions', 'defect-back', 'defect-action-status'].forEach(id => add(id, id === 'defect-back' ? 'button' : 'div', 'defect-detail-panel'));
  add('defect-action-form', 'form', 'defect-detail-panel');
  ['defect-action-title', 'defect-action-help', 'defect-action-error', 'defect-resolution-wrap', 'defect-action-assignee-wrap', 'defect-action-evidence'].forEach(id => add(id, 'div', 'defect-action-form'));
  add('defect-resolution', 'textarea', 'defect-resolution-wrap'); add('defect-action-assignee', 'input', 'defect-action-assignee-wrap');
  ['defect-create-heading', 'defect-create-error', 'defect-create-context', 'defect-create-target-wrap', 'defect-create-fields'].forEach(id => add(id, 'div', 'defect-create-form'));
  add('defect-create-target', 'select', 'defect-create-target-wrap');
  for (const prefix of ['defect-create', 'defect-action']) {
    for (const suffix of ['run', 'case', 'attempt']) add(prefix + '-' + suffix, 'select', prefix === 'defect-create' ? 'defect-create-form' : 'defect-action-evidence').required = prefix === 'defect-create';
    add(prefix + '-submit', 'button', prefix + '-form'); add(prefix + '-cancel', 'button', prefix + '-form');
  }
  ['title', 'description', 'assignee', 'severity', 'priority'].forEach(suffix => add('defect-create-' + suffix, ['severity', 'priority'].includes(suffix) ? 'select' : 'input', 'defect-create-fields'));
  nodes.get('defect-create-title').required = true;
  function request(method, url, body) {
    let resolve, reject;
    const promise = new Promise((ok, error) => { resolve = ok; reject = error; });
    calls.push({ method, url, body: body && plain(body), resolve, reject });
    return promise;
  }
  const api = { get: url => request('GET', url), post: (url, body) => request('POST', url, body) };
  const nav = {
    read: () => route,
    select(view, value) { route = { view, ...plain(value) }; },
    href(view, value) { return 'https://example.test/veriqra/index.html?' + new URLSearchParams(value).toString() + '#' + view; },
    open(view, value) { opened.push({ view, ...plain(value) }); route = { view, ...plain(value) }; document.dispatchEvent({ type: 'veriqra:view', detail: { view, projectId: value.projectId, context: route } }); }
  };
  const window = { VeriqraApi: api, VeriqraQaNavigation: nav, confirm: () => true, I18n: {
    t: (key, values, fallback) => (fallback || key).replace(/\{(\w+)\}/g, (_, name) => values?.[name] ?? ''),
    enumLabel: value => value, formatDateTime: value => value || '—', formatNumber: value => String(value)
  } };
  class CustomEvent { constructor(type, options) { this.type = type; this.detail = options.detail; } }
  vm.runInNewContext(source, { window, document, jQuery: $, CustomEvent, location: { hash: '#defects' } });
  const emit = (type, detail) => document.dispatchEvent({ type, detail });
  const fire = (node, type = 'click') => Promise.all((node.handlers.get(type) || []).map(fn => fn.call(node, { preventDefault() {} })));
  emit('veriqra:project', { project: { id: Number(context.projectId || 1) } });
  return { $, nodes, calls, emit, fire, opened, emitted, route: () => route, nav, focused: () => document.activeElement };
}

const defect = (id = 9, status = 'OPEN') => ({ id, keyNo: id, projectId: 1, title: 'Broken login', status, severity: 'HIGH', priority: 'HIGH', version: 2 });
const evidence = (attemptId = 6) => ({ attemptId, runId: 4, runCaseId: 5, attemptNo: attemptId - 5, outcome: 'FAIL', failureMessage: 'failed' });
const run = { run: { id: 4, name: 'Regression' }, cases: [{ runCaseId: 5, testCaseId: 3, snapshotTitle: 'Frozen login' }] };
const fail = id => ({ id, runCaseId: 5, attemptNo: id - 5, outcome: 'FAIL', executedAt: '2026-10-01', comment: 'Actual failure' });
function button(ui, container, label) { return ui.nodes.get(container).all().find(node => node.tag === 'button' && node.content === label); }

async function loadPrefill(ui) {
  ui.calls.find(call => call.url === 'projects/1/runs').resolve([{ id: 4, name: 'Regression' }]); await flush();
  ui.calls.find(call => call.url === 'projects/1/runs/4').resolve(run); await flush();
  ui.calls.find(call => call.url.endsWith('/attempts')).resolve([fail(6), { ...fail(7), outcome: 'PASS' }]); await flush();
}

test('defect detail shares enrichment and produces exact source Attempt and original Case links', async () => {
  const ui = browser({ view: 'defects', projectId: '1', defectId: '9' });
  ui.calls[0].resolve({ defect: defect(), evidence: [evidence(6), evidence(7)] }); await flush();
  assert.deepEqual(ui.calls.map(call => call.url), ['projects/1/defects/9', 'projects/1/runs/4', 'projects/1/runs/4/cases/5/attempts']);
  ui.calls[1].resolve(run); ui.calls[2].resolve([fail(6), fail(7)]); await flush();
  const anchors = ui.nodes.get('defect-evidence').all().filter(node => node.tag === 'a');
  const exact = anchors.find(node => node.content === 'Open source attempt');
  const href = new URL(exact.attrs.href);
  assert.equal(href.hash, '#runs'); assert.equal(href.searchParams.get('runCaseId'), '5'); assert.equal(href.searchParams.get('attemptId'), '6');
  await ui.fire(exact);
  assert.deepEqual(ui.opened[0], { view: 'runs', projectId: '1', runId: '4', runCaseId: '5', attemptId: '6', defectId: '9' });
  const original = anchors.find(node => node.content === 'Open original Test Case');
  assert.equal(new URL(original.attrs.href).searchParams.get('testCaseId'), '3');
});

test('failed enrichment does not claim empty retests and retry preserves evidence identity', async () => {
  const ui = browser({ view: 'defects', projectId: '1', defectId: '9' });
  ui.calls[0].resolve({ defect: defect(), evidence: [evidence()] }); await flush();
  ui.calls[1].resolve(run); ui.calls[2].reject({ status: 500, message: 'Unavailable' }); await flush();
  assert.match(ui.$('#defect-evidence').text(), /Retest status is unavailable/);
  assert.doesNotMatch(ui.$('#defect-evidence').text(), /No later attempt recorded/);
  const retry = button(ui, 'defect-evidence', 'Retry evidence');
  const pending = ui.fire(retry); await flush();
  assert.equal(retry.disabled, true);
  ui.calls[3].resolve(run); ui.calls[4].resolve([fail(6)]); await pending; await flush();
  assert.match(ui.$('#defect-evidence').text(), /No later attempt recorded/);
  assert.doesNotMatch(ui.$('#defect-evidence').text(), /Retest status is unavailable/);
});

test('late evidence mutation controls remain disabled while a transition is pending', async () => {
  const ui = browser({ view: 'defects', projectId: '1', defectId: '9' });
  ui.calls[0].resolve({ defect: defect(), evidence: [evidence()] }); await flush();
  const pending = ui.fire(button(ui, 'defect-actions', 'Start work')); await flush();
  const transition = ui.calls.find(call => call.method === 'POST');
  assert.deepEqual(transition.body, { expectedVersion: 2 });
  ui.calls[1].resolve(run); ui.calls[2].resolve([fail(6)]); await flush();
  const remove = button(ui, 'defect-evidence', 'Remove link (correction)');
  assert.equal(remove.disabled, true);
  await ui.fire(remove); assert.equal(ui.calls.filter(call => call.method === 'POST').length, 1);
  transition.reject({ status: 409, message: 'Conflict' }); await pending;
  assert.equal(remove.disabled, false);
});

test('route creation prefills numeric API identities and reports the verified association', async () => {
  const ui = browser({ view: 'defects', projectId: '1', action: 'create', runId: '4', runCaseId: '5', attemptId: '6' });
  await loadPrefill(ui);
  assert.equal(ui.$('#defect-create-attempt').val(), '6'); assert.equal(ui.$('#defect-create-submit').prop('disabled'), false);
  ui.$('#defect-create-title').val('Failure'); ui.$('#defect-create-severity').val('HIGH'); ui.$('#defect-create-priority').val('HIGH');
  const pending = ui.fire(ui.nodes.get('defect-create-form'), 'submit'); await flush();
  const creation = ui.calls.find(call => call.method === 'POST');
  assert.deepEqual(creation.body, { failureAttemptId: 6, title: 'Failure', description: null, severity: 'HIGH', priority: 'HIGH', assigneeId: null });
  creation.resolve(defect()); await flush();
  assert.deepEqual(plain(ui.emitted.find(event => event.type === 'veriqra:defect-linked').detail), { projectId: '1', defectId: '9', runId: '4', runCaseId: '5', attemptId: '6' });
  assert.equal(ui.route().defectId, '9'); assert.equal(ui.route().action, undefined);
  ui.calls.at(-1).resolve({ defect: defect(), evidence: [] }); await pending;
});

test('association loads one Defect list and posts only failureAttemptId to the selected existing Defect', async () => {
  const ui = browser({ view: 'defects', projectId: '1', action: 'link', runId: '4', runCaseId: '5', attemptId: '6' });
  ui.calls.find(call => call.url === 'projects/1/defects').resolve([defect(), defect(10, 'CLOSED')]);
  await loadPrefill(ui);
  assert.equal(ui.nodes.get('defect-create-title').disabled, true);
  assert.equal(ui.nodes.get('defect-create-target').children.some(node => node.value === '10'), false);
  ui.$('#defect-create-target').val('9'); await ui.fire(ui.nodes.get('defect-create-target'), 'change');
  const pending = ui.fire(ui.nodes.get('defect-create-form'), 'submit'); await flush();
  await ui.fire(ui.nodes.get('defect-create-form'), 'submit');
  assert.equal(ui.calls.filter(call => call.method === 'POST').length, 1, 'A second submit while the association is pending must not write again.');
  const write = ui.calls.find(call => call.method === 'POST');
  assert.equal(write.url, 'projects/1/defects/9/evidence'); assert.deepEqual(write.body, { failureAttemptId: 6 });
  assert.equal(ui.calls.filter(call => /\/defects\/\d+$/.test(call.url)).length, 0);
  write.resolve(undefined); await flush(); ui.calls.at(-1).resolve({ defect: defect(), evidence: [] }); await pending;
  assert.equal(ui.route().defectId, '9');
});

test('late evidence from an earlier Defect cannot replace the newer Defect detail', async () => {
  const ui = browser({ view: 'defects', projectId: '1', defectId: '9' });
  ui.calls[0].resolve({ defect: defect(), evidence: [evidence()] }); await flush();
  const oldRun = ui.calls[1], oldHistory = ui.calls[2];
  ui.nav.open('defects', { projectId: '1', defectId: '10' }); await flush();
  ui.calls[3].resolve({ defect: defect(10), evidence: [] }); await flush();
  oldRun.resolve(run); oldHistory.resolve([fail(6)]); await flush();
  assert.match(ui.$('#defect-detail-title').text(), /BUG-010/);
  assert.equal(ui.$('#defect-evidence').text(), '');
  assert.match(ui.$('#defect-evidence-status').text(), /No linked failure evidence/);
});

test('successful removal reports its original verified association even after navigating away', async () => {
  const ui = browser({ view: 'defects', projectId: '1', defectId: '9' });
  ui.calls[0].resolve({ defect: defect(), evidence: [evidence()] }); await flush();
  ui.calls[1].resolve(run); ui.calls[2].resolve([fail(6)]); await flush();
  const pending = ui.fire(button(ui, 'defect-evidence', 'Remove link (correction)')); await flush();
  const removal = ui.calls[3];
  assert.equal(removal.url, 'projects/1/defects/9/evidence/6/remove');
  ui.nav.open('runs', { projectId: '1', runId: '4', runCaseId: '5', attemptId: '6' });
  removal.resolve(undefined); await pending;
  assert.deepEqual(plain(ui.emitted.find(event => event.type === 'veriqra:defect-unlinked').detail),
    { projectId: '1', defectId: '9', attemptId: '6', runId: '4', runCaseId: '5' });
  assert.equal(ui.calls.filter(call => call.url === 'projects/1/defects/9').length, 1, 'The stale detail must not reopen.');
});

for (const action of ['create', 'link']) {
  test('successful ' + action + ' reports its original association after changing projects', async () => {
    const ui = browser({ view: 'defects', projectId: '1', action, runId: '4', runCaseId: '5', attemptId: '6' });
    if (action === 'link') ui.calls.find(call => call.url === 'projects/1/defects').resolve([defect()]);
    await loadPrefill(ui);
    if (action === 'link') {
      ui.$('#defect-create-target').val('9'); await ui.fire(ui.nodes.get('defect-create-target'), 'change');
    } else {
      ui.$('#defect-create-title').val('Failure'); ui.$('#defect-create-severity').val('HIGH'); ui.$('#defect-create-priority').val('HIGH');
    }
    const pending = ui.fire(ui.nodes.get('defect-create-form'), 'submit'); await flush();
    const write = ui.calls.find(call => call.method === 'POST');
    ui.nav.open('runs', { projectId: '2', runId: '8' }); ui.emit('veriqra:project', { project: { id: 2 } });
    write.resolve(action === 'link' ? undefined : defect()); await pending;
    assert.deepEqual(plain(ui.emitted.find(event => event.type === 'veriqra:defect-linked').detail),
      { projectId: '1', defectId: '9', runId: '4', runCaseId: '5', attemptId: '6' });
    assert.deepEqual(ui.route(), { view: 'runs', projectId: '2', runId: '8' });
    assert.equal(ui.calls.filter(call => call.url === 'projects/1/defects/9').length, 0, 'A completed stale write must not reopen detail.');
  });
}

for (const mode of ['add', 'reopen']) {
  test('successful ' + mode + ' reports its captured source after changing projects', async () => {
    const ui = browser({ view: 'defects', projectId: '1', defectId: '9' });
    ui.calls[0].resolve({ defect: defect(9, mode === 'reopen' ? 'CLOSED' : 'OPEN'), evidence: [evidence()] }); await flush();
    ui.calls[1].resolve(run); ui.calls[2].resolve([fail(6)]); await flush();
    await ui.fire(button(ui, 'defect-actions', mode === 'reopen' ? 'Reopen with new FAIL' : 'Add failure evidence'));
    ui.calls.at(-1).resolve([{ id: 4, name: 'Regression' }]); await flush();
    ui.$('#defect-action-run').val('4'); await ui.fire(ui.nodes.get('defect-action-run'), 'change');
    ui.calls.at(-1).resolve(run); await flush();
    ui.$('#defect-action-case').val('5'); await ui.fire(ui.nodes.get('defect-action-case'), 'change');
    ui.calls.at(-1).resolve([fail(6), fail(7)]); await flush();
    ui.$('#defect-action-attempt').val('7'); await ui.fire(ui.nodes.get('defect-action-attempt'), 'change');
    if (mode === 'reopen') ui.$('#defect-action-assignee').val('11');
    const pending = ui.fire(ui.nodes.get('defect-action-form'), 'submit'); await flush();
    const write = ui.calls.find(call => call.method === 'POST');
    assert.equal(write.url, 'projects/1/defects/9/' + (mode === 'add' ? 'evidence' : 'reopen'));
    ui.nav.open('runs', { projectId: '2', runId: '8' }); ui.emit('veriqra:project', { project: { id: 2 } });
    write.resolve(undefined); await pending;
    assert.deepEqual(plain(ui.emitted.find(event => event.type === 'veriqra:defect-linked').detail),
      { projectId: '1', defectId: '9', runId: '4', runCaseId: '5', attemptId: '7' });
    assert.deepEqual(ui.route(), { view: 'runs', projectId: '2', runId: '8' });
    assert.equal(ui.calls.filter(call => call.url === 'projects/1/defects/9').length, 1, 'A completed stale write must not reopen detail.');
  });
}

test('canonical failureAttemptId route and missing FAIL source cannot enable an invalid submit', async () => {
  const ui = browser({ view: 'defects', projectId: '1', action: 'create', runId: '4', runCaseId: '5', failureAttemptId: '99' });
  await loadPrefill(ui);
  assert.equal(ui.$('#defect-create-submit').prop('disabled'), true);
  assert.match(ui.$('#defect-create-error').text(), /no longer available/);
  ui.$('#defect-create-attempt').val('6'); await ui.fire(ui.nodes.get('defect-create-attempt'), 'change');
  assert.equal(ui.route().failureAttemptId, '6');
  assert.equal(ui.$('#defect-create-submit').prop('disabled'), false);
});

test('rapid picker changes and project changes reject stale FAIL selections and stale detail responses', async () => {
  const ui = browser({ view: 'defects', projectId: '1', action: 'create', runId: '4', runCaseId: '5', attemptId: '6' });
  ui.calls[0].resolve([{ id: 4, name: 'First' }, { id: 8, name: 'Second' }]); await flush();
  const first = ui.calls[1];
  ui.$('#defect-create-run').val('8'); await ui.fire(ui.nodes.get('defect-create-run'), 'change');
  ui.calls[2].resolve({ run: { id: 8 }, cases: [{ runCaseId: 12, snapshotTitle: 'Second case' }] }); await flush();
  first.resolve(run); await flush();
  assert.equal(ui.nodes.get('defect-create-case').children.some(node => node.value === '5'), false);
  ui.$('#defect-create-attempt').val('6'); await ui.fire(ui.nodes.get('defect-create-attempt'), 'change');
  assert.equal(ui.$('#defect-create-submit').prop('disabled'), true);
  ui.nav.open('defects', { projectId: '1', defectId: '9' }); await flush();
  const stale = ui.calls.at(-1);
  ui.nav.select('defects', { projectId: '2' }); ui.emit('veriqra:project', { project: { id: 2 } });
  stale.resolve({ defect: defect(), evidence: [evidence()] }); await flush();
  assert.equal(ui.$('#defect-detail').text(), '');
  assert.equal(ui.calls.filter(call => call.url === 'projects/1/runs/4/cases/5/attempts').length, 0);
});

test('locale changes keep an unsubmitted resolution draft and selected FAIL evidence', async () => {
  const ui = browser({ view: 'defects', projectId: '1', defectId: '9' });
  ui.calls[0].resolve({ defect: defect(9, 'IN_PROGRESS'), evidence: [] }); await flush();
  await ui.fire(button(ui, 'defect-actions', 'Resolve'));
  ui.$('#defect-resolution').val('Working draft');
  ui.emit('veriqra:localechange', {});
  assert.equal(ui.$('#defect-resolution').val(), 'Working draft');
  assert.equal(ui.$('#defect-action-form').hasClass('d-none'), false);
  assert.equal(ui.calls.length, 1);
});

test('Defect list and detail failure end loading and offer scoped GET Retry', async () => {
  for (const detailed of [false, true]) {
    const ui = browser({ view: 'defects', projectId: '1', ...(detailed ? { defectId: '9' } : {}) });
    ui.calls[0].reject({ status: 500, message: 'Defects unavailable' }); await flush();
    assert.equal(ui.$(detailed ? '#defect-detail-title' : '#defect-list-status').text(), 'Unavailable');
    const retry = button(ui, detailed ? 'defect-actions' : 'defect-list', 'Retry');
    const loading = ui.fire(retry); assert.equal(ui.calls.at(-1).method, 'GET');
    assert.equal(ui.calls.at(-1).url, 'projects/1/defects' + (detailed ? '/9' : ''));
    ui.calls.at(-1).resolve(detailed ? { defect: defect(), evidence: [] } : []); await loading;
    assert.match(ui.$(detailed ? '#defect-detail-title' : '#defect-list-status').text(), detailed ? /Broken login/ : /No defects/);
    assert.equal(ui.calls.every(call => call.method === 'GET'), true);
    ui.nav.open('defects', { projectId: '1', action: 'create' }); const before = ui.calls.length;
    await ui.fire(retry); assert.equal(ui.calls.length, before, 'Obsolete Retry cannot reopen another panel');
  }
});

test('each evidence picker failure stays unavailable across locale and Retry preserves the unsaved draft', async () => {
  for (const field of ['run', 'case', 'attempt']) {
    const ui = browser({ view: 'defects', projectId: '1', action: 'create', runId: '4', runCaseId: '5', attemptId: '6' });
    ui.$('#defect-create-title').val('Unsaved report'); ui.$('#defect-create-description').val('Typed details');
    if (field !== 'run') { ui.calls.at(-1).resolve([{ id: 4, name: 'Regression' }]); await flush(); }
    if (field === 'attempt') { ui.calls.at(-1).resolve(run); await flush(); }
    ui.calls.at(-1).reject({ status: 500, message: field + ' unavailable' }); await flush();
    const select = ui.$('#defect-create-' + field);
    assert.equal(select[0].children[0].content, 'Unavailable'); assert.equal(select.prop('disabled'), true);
    ui.emit('veriqra:localechange', {});
    assert.equal(select[0].children[0].content, 'Unavailable'); assert.equal(ui.$('#defect-create-submit').prop('disabled'), true);
    const loading = ui.fire(ui.nodes.get('defect-create-' + field + '-retry'));
    assert.equal(ui.calls.at(-1).method, 'GET'); ui.calls.at(-1).resolve(field === 'case' ? { cases: [] } : []); await loading;
    assert.notEqual(select[0].children[0].content, 'Unavailable');
    assert.equal(ui.$('#defect-create-title').val(), 'Unsaved report'); assert.equal(ui.$('#defect-create-description').val(), 'Typed details');
    assert.equal(ui.calls.every(call => call.method === 'GET'), true);
  }
});

test('failed existing-defect picker remains disabled on locale and retries independently of evidence', async () => {
  const ui = browser({ view: 'defects', projectId: '1', action: 'link' });
  ui.calls.find(call => call.url.endsWith('/runs')).resolve([]);
  ui.calls.find(call => call.url.endsWith('/defects')).reject({ status: 500, message: 'Defect choices unavailable' }); await flush();
  ui.emit('veriqra:localechange', {});
  assert.equal(ui.$('#defect-create-target').prop('disabled'), true);
  assert.equal(ui.$('#defect-create-target')[0].children[0].content, 'Unavailable');
  const loading = ui.fire(ui.nodes.get('defect-create-target-retry')); ui.calls.at(-1).resolve([]); await loading;
  assert.match(ui.$('#defect-create-target')[0].children[0].content, /No open defects/);
  assert.equal(ui.calls.filter(call => call.url.endsWith('/runs')).length, 1);
  assert.equal(ui.calls.every(call => call.method === 'GET'), true);
});

test('opening and cancelling a Defect resolution panel moves focus to visible controls', async () => {
  const ui = browser({ view: 'defects', projectId: '1', defectId: '9' });
  ui.calls[0].resolve({ defect: defect(9, 'IN_PROGRESS'), evidence: [] }); await flush();
  await ui.fire(button(ui, 'defect-actions', 'Resolve'));
  assert.equal(ui.focused(), ui.$('#defect-resolution')[0]);
  await ui.fire(ui.$('#defect-action-cancel')[0]);
  assert.equal(ui.$('#defect-action-form').hasClass('d-none'), true); assert.equal(ui.focused(), ui.$('#defect-detail-title')[0]);
});

for (const state of ['pending', 'failed']) {
  test('locale change restores the selected Defect while detail is ' + state, async () => {
    const ui = browser({ view: 'defects', projectId: '1', defectId: '9' });
    const first = ui.calls[0];
    if (state === 'failed') { first.reject({ status: 500, message: 'Detail unavailable' }); await flush(); }
    ui.emit('veriqra:localechange', {}); await flush();
    assert.equal(ui.calls[1].url, 'projects/1/defects/9');
    assert.equal(ui.$('#defect-detail-panel').hasClass('d-none'), false);
    assert.equal(ui.$('#defect-list-panel').hasClass('d-none'), true);
    assert.equal(ui.route().defectId, '9');
    if (state === 'pending') { first.resolve({ defect: defect(10), evidence: [] }); await flush(); }
    ui.calls[1].resolve({ defect: defect(), evidence: [] }); await flush();
    assert.match(ui.$('#defect-detail-title').text(), /BUG-009/);
    assert.equal(ui.calls.filter(call => call.url === 'projects/1/defects').length, 0);
  });
}
