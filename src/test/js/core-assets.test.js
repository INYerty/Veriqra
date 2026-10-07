const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const source = fs.readFileSync(path.resolve(__dirname, '../../main/webapp/assets/js/test-assets.js'), 'utf8');
const appSource = fs.readFileSync(path.resolve(__dirname, '../../main/webapp/assets/js/app.js'), 'utf8');
const flush = async () => { for (let i = 0; i < 8; i++) await Promise.resolve(); };

// Executes the production module. This DOM fixture models its jQuery operations, while requests stay deferred.
function browser(initial = { view: 'requirements', projectId: '7', requirementId: '10' }, integrated = false) {
  const nodes = new Map(), requests = [], nativeListeners = new Map(), navigation = [];
  let route = { ...initial };
  class Node {
    constructor(tag = 'div', attributes = {}) {
      this.tag = tag; this.attributes = { ...attributes }; this.classes = new Set((attributes.class || '').split(/\s+/));
      this.content = ''; this.value = ''; this.disabled = false; this.childrenNodes = []; this.handlers = new Map(); this.parent = null;
    }
    reset() { for (const node of nodes.values()) if (['input', 'textarea', 'select'].includes(node.tag)) node.value = ''; }
    reportValidity() { return true; }
  }
  function descendants(node) { return node.childrenNodes.flatMap(child => child instanceof Node ? [child, ...descendants(child)] : []); }
  function matches(node, selector) {
    if (selector.startsWith('#')) return node.attributes.id === selector.slice(1);
    if (selector.startsWith('.')) return node.classes.has(selector.slice(1));
    return node.tag === selector;
  }
  class Collection {
    constructor(items) { this.items = items; this.length = items.length; items.forEach((node, index) => { this[index] = node; }); }
    each(fn) { this.items.forEach((node, index) => fn.call(node, index, node)); return this; }
    on(names, fn) { return this.each(function () { names.split(' ').forEach(name => { if (!this.handlers.has(name)) this.handlers.set(name, []); this.handlers.get(name).push(fn); }); }); }
    text(value) { if (value === undefined) return this.items[0]?.content; return this.each(function () { this.content = String(value); }); }
    val(value) {
      if (value === undefined) { const node = this.items[0]; return node?.value || (node?.tag === 'select' ? node.childrenNodes[0]?.value || '' : ''); }
      return this.each(function () { this.value = String(value == null ? '' : value); });
    }
    empty() { return this.each(function () { this.childrenNodes = []; this.content = ''; this.value = ''; }); }
    append(...children) { return this.each(function () { children.flatMap(child => child instanceof Collection ? child.items : [child]).forEach(child => { if (child instanceof Node) child.parent = this; this.childrenNodes.push(child); }); }); }
    addClass(value) { return this.each(function () { value.split(' ').forEach(name => this.classes.add(name)); }); }
    removeClass(value) { return this.each(function () { value.split(' ').forEach(name => this.classes.delete(name)); }); }
    toggleClass(name, enabled) { return enabled ? this.addClass(name) : this.removeClass(name); }
    hasClass(name) { return this.items[0]?.classes.has(name); }
    prop(name, value) { if (value === undefined) return this.items[0]?.[name]; return this.each(function () { this[name] = value; }); }
    attr(name, value) { if (value === undefined) return this.items[0]?.attributes[name]; return this.each(function () { this.attributes[name] = String(value); }); }
    removeAttr(name) { return this.each(function () { delete this.attributes[name]; }); }
    find(selector) { return new Collection(selector.split(',').flatMap(part => this.items.flatMap(node => descendants(node).filter(child => matches(child, part.trim().replace(/^#\S+\s+/, '')))))); }
    children() { return new Collection(this.items.flatMap(node => node.childrenNodes).filter(node => node instanceof Node)); }
    map(fn) { const values = this.items.map((node, index) => fn.call(node, index, node)); return { get: () => values }; }
    get() { return this.items; }
    eq(index) { return new Collection(this.items[index] ? [this.items[index]] : []); }
    trigger(name) { if (name === 'focus') return this; this.items.forEach(node => fire(node, name)); return this; }
    prev(selector) { const node = this.items[0], siblings = node?.parent?.childrenNodes || [], index = siblings.indexOf(node); return new Collection(index > 0 && matches(siblings[index - 1], selector) ? [siblings[index - 1]] : []); }
    next(selector) { const node = this.items[0], siblings = node?.parent?.childrenNodes || [], index = siblings.indexOf(node); return new Collection(siblings[index + 1] && matches(siblings[index + 1], selector) ? [siblings[index + 1]] : []); }
    insertBefore(target) { return move(this.items[0], target.items[0], 0); }
    insertAfter(target) { return move(this.items[0], target.items[0], 1); }
    remove() { return this.each(function () { this.parent.childrenNodes.splice(this.parent.childrenNodes.indexOf(this), 1); }); }
  }
  function move(node, target, offset) { const siblings = node.parent.childrenNodes; siblings.splice(siblings.indexOf(node), 1); siblings.splice(siblings.indexOf(target) + offset, 0, node); return new Collection([node]); }
  const documentNode = new Node('document');
  const windowNode = new Node('window');
  function $(selector) {
    if (selector === document) return new Collection([documentNode]);
    if (selector === window) return new Collection([windowNode]);
    if (selector instanceof Node) return new Collection([selector]);
    if (selector instanceof Collection) return selector;
    if (selector.startsWith('<')) {
      const attributes = {};
      for (const match of selector.matchAll(/([\w-]+)="([^"]*)"/g)) attributes[match[1]] = match[2];
      return new Collection([new Node(selector.match(/^<(\w+)/)[1], attributes)]);
    }
    if (selector.includes(',')) return new Collection(selector.split(',').flatMap(part => $(part.trim()).items));
    if (selector.includes(' ')) { const [root, ...parts] = selector.split(' '); return $(root).find(parts.join(' ')); }
    if (!nodes.has(selector)) {
      const tag = /form$/.test(selector) ? 'form' : /target|priority|status$/.test(selector) ? 'select' : /title|description|preconditions$/.test(selector) ? 'input' : 'div';
      nodes.set(selector, new Node(tag, { id: selector.slice(1), class: /panel|form$/.test(selector) ? 'd-none' : '' }));
    }
    return new Collection([nodes.get(selector)]);
  }
  function fire(node, name, fields = {}) {
    const event = { button: 0, preventDefault() { this.defaultPrevented = true; }, ...fields };
    return Promise.all((node.handlers.get(name) || []).map(fn => fn.call(node, event)));
  }
  const document = {
    createTextNode: text => String(text), addEventListener: (name, fn) => nativeListeners.set(name, fn),
    dispatchEvent(event) { fire(documentNode, event.type, { originalEvent: event }); nativeListeners.get(event.type)?.(event); }
  };
  const api = {};
  for (const method of ['get', 'post', 'put']) api[method] = (url, body) => {
    let resolve, reject;
    const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
    promise.done = fn => { promise.then(fn, () => {}); return promise; };
    promise.fail = fn => { promise.then(() => {}, fn); return promise; };
    promise.always = fn => { promise.then(fn, fn); return promise; };
    requests.push({ method, url, body, resolve, reject }); return promise;
  };
  const nav = {
    read: () => ({ ...route }), href: (view, context) => 'index.html?' + new URLSearchParams(context) + '#' + view,
    select(view, context) { route = { view, ...context }; },
    open(view, context) { route = { view, ...context }; navigation.push({ ...route }); document.dispatchEvent({ type: 'veriqra:view', detail: { view, projectId: context.projectId, context } }); }
  };
  const window = { VeriqraApi: api, VeriqraQaNavigation: nav, confirm: () => true, I18n: {
    t: (key, values, fallback) => (fallback || key).replace(/\{(\w+)\}/g, (_, name) => values?.[name] ?? ''),
    enumLabel: value => value, formatNumber: String, formatDateTime: String, init: async () => {}
  } };
  const location = new URL('http://127.0.0.1:9000/veriqra/index.html?' + new URLSearchParams(Object.fromEntries(Object.entries(initial).filter(([key]) => key !== 'view'))) + '#' + initial.view);
  window.history = { pushState(_, __, href) { location.href = href; }, replaceState(_, __, href) { location.href = href; } };
  document.baseURI = 'http://127.0.0.1:9000/veriqra/';
  const context = { window, document, jQuery: $, URL, URLSearchParams, location,
    sessionStorage: { getItem: () => null, setItem() {}, removeItem() {} }, Math,
    CustomEvent: class { constructor(type, options) { this.type = type; this.detail = options.detail; } } };
  if (integrated) vm.runInNewContext(appSource, context);
  vm.runInNewContext(source, context);
  const dispatchProject = (id = '7', status = 'ACTIVE') => document.dispatchEvent({ type: 'veriqra:project', detail: { project: { id, status } } });
  const click = (selector, fields) => fire($(selector)[0], 'click', fields);
  const submit = selector => fire($(selector)[0], 'submit');
  const caseRow = (id = 20, status = 'DRAFT') => ({ id, keyNo: id, title: 'Case ' + id, status, priority: 'MEDIUM', version: 3 });
  const requirement = (id = 10, status = 'ACTIVE') => ({ id, keyNo: id, title: 'Requirement ' + id, status, priority: 'MEDIUM', version: 2 });
  return { $, nodes, requests, nav: integrated ? window.VeriqraQaNavigation : nav, navigation, dispatchProject, click, submit, caseRow, requirement, fire,
    pageshow: () => fire(windowNode, 'pageshow'), locale: () => document.dispatchEvent({ type: 'veriqra:localechange' }),
    location, route: () => integrated ? window.VeriqraQaNavigation.read() : route };
}

test('locale during pending requirement or case detail preserves the selected route and rejects the earlier response', async () => {
  for (const view of ['requirements', 'test-cases']) {
    const field = view === 'requirements' ? 'requirementId' : 'testCaseId';
    const id = view === 'requirements' ? '10' : '20';
    const ui = browser({ view, projectId: '7', [field]: id, sourcePlanId: '30' }); ui.dispatchProject();
    ui.locale(); await flush();
    assert.equal(ui.requests[1].url, 'projects/7/' + view + '/' + id);
    assert.equal(ui.route()[field], id); assert.equal(ui.route().sourcePlanId, '30');
    const row = view === 'requirements' ? ui.requirement() : ui.caseRow();
    ui.requests[0].resolve(view === 'requirements' ? { ...row, title: 'Stale response' } : { testCase: { ...row, title: 'Stale response' }, steps: [] });
    await flush(); assert.equal(ui.$('#asset-detail-title').text(), 'Loading…');
    assert.equal(ui.requests.length, 2, 'The abandoned detail read cannot start trace reads.');
    ui.requests[1].resolve(view === 'requirements' ? row : { testCase: row, steps: [] }); await flush();
    if (view === 'requirements') { ui.requests[2].resolve([]); await flush(); }
    assert.equal(ui.route()[field], id); assert.equal(ui.$('#asset-detail-panel').hasClass('d-none'), false);
    assert.equal(ui.requests.some(call => call.url === 'projects/7/' + view), false);
  }
});

test('locale recovers failed detail from the current route rather than its previous requested identity', async () => {
  const ui = browser({ view: 'test-cases', projectId: '7', testCaseId: '20' }); ui.dispatchProject();
  ui.requests[0].reject({ status: 500, message: 'Unavailable' }); await flush();
  ui.nav.select('test-cases', { projectId: '7', testCaseId: '21', sourceRequirementId: '11' });
  ui.locale(); await flush(); assert.equal(ui.requests[1].url, 'projects/7/test-cases/21');
  ui.requests[1].resolve({ testCase: ui.caseRow(21), steps: [] }); await flush();
  assert.match(ui.$('#asset-detail-title').text(), /Case 21/); assert.equal(ui.route().testCaseId, '21');
  assert.equal(ui.route().sourceRequirementId, '11'); assert.equal(ui.requests.length, 2);
});

test('locale respects the current case route over an older rendered detail and keeps its reverse trace lazy', async () => {
  const ui = browser({ view: 'test-cases', projectId: '7', testCaseId: '20' }); ui.dispatchProject();
  ui.requests[0].resolve({ testCase: ui.caseRow(), steps: [] }); await flush();
  ui.nav.select('test-cases', { projectId: '7', testCaseId: '21' });
  ui.locale(); await flush(); assert.equal(ui.requests[1].url, 'projects/7/test-cases/21');
  ui.requests[1].resolve({ testCase: ui.caseRow(21), steps: [] }); await flush();
  assert.equal(ui.route().testCaseId, '21'); assert.equal(ui.requests.length, 2);
  assert.equal(ui.$('#trace-add-button').prop('disabled'), true);
});

test('locale preserves an asset form draft and does not unlock stale or failed trace reads', async () => {
  const ui = browser(); ui.dispatchProject(); ui.requests[0].resolve(ui.requirement()); await flush();
  ui.locale(); await flush(); assert.equal(ui.requests[2].url, 'projects/7/requirements/10');
  ui.requests[1].resolve([{ testCase: ui.caseRow(), status: 'CONFIRMED' }]); await flush();
  assert.equal(ui.$('#asset-traces').children().length, 0); assert.equal(ui.$('#trace-add-button').prop('disabled'), true);
  ui.requests[2].resolve(ui.requirement()); await flush();
  ui.requests[3].reject({ status: 500, message: 'Trace unavailable' }); await flush();
  assert.equal(ui.$('#trace-add-button').prop('disabled'), true);
  assert.equal(ui.$('#asset-trace-retry').hasClass('d-none'), false);
  await ui.click('#asset-edit'); ui.$('#asset-title').val('Unsaved title'); ui.$('#asset-description').val('Unsaved description');
  ui.locale(); await flush(); assert.equal(ui.$('#asset-title').val(), 'Unsaved title');
  assert.equal(ui.$('#asset-description').val(), 'Unsaved description'); assert.equal(ui.requests.length, 4);
});

test('detail routes accept string IDs; reverse traceability is lazy and links retain source context', async () => {
  const ui = browser({ view: 'test-cases', projectId: '7', testCaseId: '20', sourceRequirementId: '10' });
  ui.dispatchProject(); assert.equal(ui.requests[0].url, 'projects/7/test-cases/20');
  ui.requests[0].resolve({ testCase: ui.caseRow(), steps: [] }); await flush();
  assert.equal(ui.requests.length, 1); assert.equal(ui.$('#trace-add-button').prop('disabled'), true);
  assert.equal(ui.$('#asset-trace-load').hasClass('d-none'), false);
  const back = ui.$('#asset-primary-actions').children()[0];
  assert.match(back.attributes.href, /requirementId=10/);
  await ui.fire(back, 'click', { ctrlKey: true }); assert.equal(ui.navigation.length, 0);
  await ui.fire(back, 'click'); assert.equal(ui.navigation[0].requirementId, '10');
  assert.equal(ui.navigation[0].sourceTestCaseId, '20');
});

test('failed trace read stays distinct from empty and gates links until retry succeeds', async () => {
  const ui = browser(); ui.dispatchProject(); ui.requests[0].resolve(ui.requirement()); await flush();
  assert.equal(ui.requests[1].url, 'projects/7/requirements/10/test-cases');
  assert.equal(ui.$('#trace-add-button').prop('disabled'), true);
  ui.requests[1].reject({ status: 500, message: 'Unavailable' }); await flush();
  assert.equal(ui.$('#asset-trace-retry').hasClass('d-none'), false);
  assert.match(ui.$('#asset-trace-status').text(), /could not be loaded/);
  await ui.click('#trace-add-button'); assert.equal(ui.requests.length, 2);
  const retry = ui.click('#asset-trace-retry'); ui.requests[2].resolve([]); await retry; await flush();
  assert.equal(ui.$('#trace-add-button').prop('disabled'), false);
  assert.equal(ui.$('#asset-traces').children()[0].content, 'No traceability records yet.');
});

test('reverse trace scan rejects an unrelated read failure and does not offer partial links', async () => {
  const ui = browser({ view: 'test-cases', projectId: '7', testCaseId: '20' }); ui.dispatchProject();
  ui.requests[0].resolve({ testCase: ui.caseRow(), steps: [] }); await flush();
  const loading = ui.click('#asset-trace-load'); ui.requests[1].resolve([ui.requirement(10), ui.requirement(11)]); await flush();
  ui.requests[2].resolve([{ testCase: ui.caseRow(), status: 'CONFIRMED' }]); ui.requests[3].reject({ status: 500, message: 'Unavailable' });
  await loading; assert.equal(ui.$('#asset-traces').children().length, 0);
  assert.equal(ui.$('#trace-add-button').prop('disabled'), true);
});

test('stale detail and trace reads cannot render or fan out in another project', async () => {
  const ui = browser({ view: 'test-cases', projectId: '7', testCaseId: '20' }); ui.dispatchProject();
  ui.requests[0].resolve({ testCase: ui.caseRow(), steps: [] }); await flush();
  const loading = ui.click('#asset-trace-load');
  ui.nav.select('requirements', { projectId: '8', requirementId: '11' }); ui.dispatchProject('8');
  ui.requests[1].resolve([ui.requirement()]); await loading; await flush();
  assert.equal(ui.requests.filter(call => /requirements\/10\/test-cases$/.test(call.url)).length, 0);
  ui.requests[2].resolve(ui.requirement(11)); await flush(); ui.requests[3].resolve([]); await flush();
  assert.match(ui.$('#asset-detail-title').text(), /Requirement 11/);
});

test('case creation with failed attach keeps the saved case and retry never creates it again', async () => {
  const ui = browser({ view: 'test-cases', projectId: '7', action: 'create', sourceRequirementId: '10' }); ui.dispatchProject();
  ui.$('#asset-title').val('New case'); ui.$('#asset-priority').val('MEDIUM');
  const saving = ui.submit('#asset-form'); assert.equal(ui.requests[0].method, 'post');
  ui.requests[0].resolve(ui.caseRow()); await flush();
  assert.equal(ui.requests[1].url, 'projects/7/requirements/10/test-cases/20');
  assert.equal(JSON.stringify(ui.requests[1].body), '{}');
  ui.requests[1].reject({ status: 500, message: 'Unavailable' }); await flush();
  ui.requests[2].resolve({ testCase: ui.caseRow(), steps: [] }); await saving; await flush();
  assert.equal(ui.$('#asset-partial-link-panel').hasClass('d-none'), false);
  assert.equal(ui.route().testCaseId, '20'); assert.equal(ui.route().sourceRequirementId, '10');
  const retrying = ui.click('#asset-partial-link-retry');
  assert.equal(ui.requests[3].url, 'projects/7/requirements/10/test-cases/20');
  ui.requests[3].resolve(); await flush(); ui.requests[4].resolve({ testCase: ui.caseRow(), steps: [] }); await flush();
  await retrying; await flush();
  assert.equal(ui.requests.filter(call => call.method === 'post' && call.url === 'projects/7/test-cases').length, 1);
  assert.equal(ui.$('#asset-partial-link-panel').hasClass('d-none'), true);
  assert.equal(ui.requests.length, 5, 'Successful attach must not automatically scan all requirement links.');
  assert.equal(ui.$('#asset-trace-load').hasClass('d-none'), false);
});

test('saved case and failed link remain visible when its detail read also fails; retry never repeats creation', async () => {
  const ui = browser({ view: 'test-cases', projectId: '7', action: 'create', sourceRequirementId: '10' }); ui.dispatchProject();
  ui.$('#asset-title').val('New case'); const saving = ui.submit('#asset-form');
  ui.requests[0].resolve(ui.caseRow()); await flush();
  ui.requests[1].reject({ status: 500, message: 'Link unavailable' }); await flush();
  assert.match(ui.$('#asset-partial-link-status').text(), /Test case #20.*requirement #10/);
  assert.equal(ui.$('#asset-partial-link-panel').hasClass('d-none'), false);
  ui.requests[2].reject({ status: 500, message: 'Details unavailable' }); await saving;
  assert.equal(ui.route().testCaseId, '20'); assert.equal(ui.route().sourceRequirementId, '10');
  assert.equal(ui.$('#asset-partial-link-panel').hasClass('d-none'), false);
  assert.match(ui.$('#asset-partial-link-status').text(), /Link unavailable/);
  assert.equal(ui.$('#asset-notice').text(), 'Details unavailable');
  assert.equal(ui.$('#asset-edit').prop('disabled'), true);
  assert.equal(ui.$('#asset-partial-link-retry').prop('disabled'), false);
  const retrying = ui.click('#asset-partial-link-retry');
  assert.equal(ui.requests[3].method, 'post'); assert.equal(ui.requests[3].url, 'projects/7/requirements/10/test-cases/20');
  assert.equal(ui.$('#asset-primary-actions').find('button')[0].disabled, true);
  ui.requests[3].resolve(); await flush(); ui.requests[4].resolve({ testCase: ui.caseRow(), steps: [] }); await retrying;
  assert.equal(ui.$('#asset-partial-link-panel').hasClass('d-none'), true);
  assert.equal(ui.requests.filter(call => call.method === 'post' && call.url === 'projects/7/test-cases').length, 1);
});

test('primary detail retry preserves saved link recovery and ignores a click after route ownership changes', async () => {
  const ui = browser({ view: 'test-cases', projectId: '7', action: 'create', sourceRequirementId: '10' }); ui.dispatchProject();
  ui.$('#asset-title').val('New case'); const saving = ui.submit('#asset-form');
  ui.requests[0].resolve(ui.caseRow()); await flush();
  ui.requests[1].reject({ status: 500, message: 'Link unavailable' }); await flush();
  ui.requests[2].reject({ status: 500, message: 'Details unavailable' }); await saving;
  const detailRetry = ui.$('#asset-primary-actions').find('button')[0];
  const loading = ui.fire(detailRetry, 'click'); assert.equal(ui.requests[3].method, 'get');
  assert.equal(ui.requests[3].url, 'projects/7/test-cases/20');
  ui.requests[3].resolve({ testCase: ui.caseRow(), steps: [] }); await loading;
  assert.match(ui.$('#asset-detail-title').text(), /Case 20/);
  assert.equal(ui.$('#asset-partial-link-panel').hasClass('d-none'), false);
  assert.match(ui.$('#asset-partial-link-status').text(), /Test case #20.*Link unavailable/);
  assert.equal(ui.route().sourceRequirementId, '10');
  ui.nav.open('test-cases', { projectId: '7', testCaseId: '21' });
  const calls = ui.requests.length; await ui.fire(detailRetry, 'click');
  assert.equal(ui.requests.length, calls, 'A detached retry button must not reopen its previous route.');
  assert.equal(ui.requests.filter(call => call.method === 'post' && call.url === 'projects/7/test-cases').length, 1);
});

test('retry conflict verifies an existing active relationship without a project-wide scan', async () => {
  const ui = browser({ view: 'test-cases', projectId: '7', action: 'create', sourceRequirementId: '10' }); ui.dispatchProject();
  ui.$('#asset-title').val('New case'); const saving = ui.submit('#asset-form');
  ui.requests[0].resolve(ui.caseRow()); await flush();
  ui.requests[1].reject({ status: 409, message: 'Conflict' }); await flush();
  assert.equal(ui.requests[2].url, 'projects/7/requirements/10/test-cases');
  ui.requests[2].resolve([{ testCase: ui.caseRow(), status: 'NEEDS_REVIEW' }]); await flush();
  ui.requests[3].resolve({ testCase: ui.caseRow(), steps: [] }); await saving;
  assert.equal(ui.$('#asset-partial-link-panel').hasClass('d-none'), true);
  assert.equal(ui.requests.length, 4);
});

test('creation completing after project navigation cannot attach or reopen the saved case', async () => {
  const ui = browser({ view: 'test-cases', projectId: '7', action: 'create', sourceRequirementId: '10' }); ui.dispatchProject();
  ui.$('#asset-title').val('New case'); const saving = ui.submit('#asset-form');
  ui.nav.select('requirements', { projectId: '8', requirementId: '11' }); ui.dispatchProject('8');
  ui.requests[0].resolve(ui.caseRow()); await saving; await flush();
  assert.equal(ui.requests.some(call => call.url.includes('/test-cases/20')), false);
  assert.equal(ui.route().projectId, '8');
});

test('pending confirm blocks editing and navigation prevents its completion reopening detail', async () => {
  const ui = browser(); ui.dispatchProject(); ui.requests[0].resolve(ui.requirement()); await flush();
  ui.requests[1].resolve([{ testCase: ui.caseRow(), status: 'NEEDS_REVIEW' }]); await flush();
  const confirm = ui.$('#asset-traces').find('button')[0]; const confirming = ui.fire(confirm, 'click');
  assert.equal(ui.$('#asset-edit').prop('disabled'), true); await ui.click('#asset-edit');
  assert.equal(ui.$('#asset-form').hasClass('d-none'), true);
  ui.nav.open('test-cases', { projectId: '7', testCaseId: '21' }); ui.requests[2].resolve(); await confirming; await flush();
  assert.equal(ui.requests.filter(call => call.url === 'projects/7/requirements/10').length, 1);
  ui.requests[3].resolve({ testCase: ui.caseRow(21), steps: [] }); await flush();
  assert.match(ui.$('#asset-detail-title').text(), /Case 21/);
});

test('same-view create navigation invalidates a pending save without leaving writes locked', async () => {
  const ui = browser({ view: 'test-cases', projectId: '7', action: 'create' }); ui.dispatchProject();
  ui.$('#asset-title').val('First case'); const saving = ui.submit('#asset-form');
  ui.nav.open('test-cases', { projectId: '7', action: 'create', sourceRequirementId: '11' });
  ui.$('#asset-title').val('Second case'); ui.requests[0].resolve(ui.caseRow()); await saving;
  assert.equal(ui.$('#asset-form').hasClass('d-none'), false); assert.equal(ui.$('#asset-title').val(), 'Second case');
  assert.equal(ui.$('#asset-save').prop('disabled'), false);
  assert.equal(ui.requests.length, 1);
});

test('archived endpoints remain navigable without edit or trace mutations', async () => {
  const ui = browser(); ui.dispatchProject(); ui.requests[0].resolve(ui.requirement(10, 'ARCHIVED')); await flush();
  ui.requests[1].resolve([{ testCase: ui.caseRow(), status: 'NEEDS_REVIEW' }]); await flush();
  assert.equal(ui.$('#asset-edit').prop('disabled'), true); assert.equal(ui.$('#trace-add-button').prop('disabled'), true);
  assert.equal(ui.$('#asset-traces').find('button').length, 0); assert.equal(ui.$('#asset-traces').find('a').length, 1);
  await ui.click('#asset-edit'); assert.equal(ui.$('#asset-form').hasClass('d-none'), true);
});

test('READY with no steps is locally rejected; conflicts preserve draft and expectedVersion', async () => {
  const ui = browser({ view: 'test-cases', projectId: '7', testCaseId: '20' }); ui.dispatchProject();
  ui.requests[0].resolve({ testCase: ui.caseRow(), steps: [] }); await flush(); await ui.click('#asset-edit');
  ui.$('#asset-status').val('READY'); await ui.submit('#asset-form');
  assert.equal(ui.requests.length, 1); assert.match(ui.$('#asset-form-error').text(), /at least one complete step/);
  ui.$('#asset-status').val('DRAFT'); ui.$('#asset-title').val('Changed case'); const saving = ui.submit('#asset-form');
  assert.equal(ui.requests[1].body.expectedVersion, 3); assert.equal(ui.requests[1].method, 'put');
  ui.requests[1].reject({ status: 409, message: 'Conflict' }); await saving;
  assert.equal(ui.$('#asset-title').val(), 'Changed case'); assert.equal(ui.$('#asset-form').hasClass('d-none'), false);
  assert.match(ui.$('#asset-form-error').text(), /cannot be edited in its current state/);
});

test('actual app navigation verifies project, opens requirement→case and returns with URL context', async () => {
  const ui = browser({ view: 'requirements', projectId: '7', requirementId: '10' }, true);
  await ui.pageshow(); await flush(); assert.equal(ui.requests[0].url, 'auth/me');
  ui.requests[0].resolve({ id: 1, username: 'tester', systemRole: 'USER' }); await flush();
  assert.equal(ui.requests[1].url, 'projects');
  ui.requests[1].resolve([{ id: 7, projectKey: 'QA', name: 'Quality', status: 'ACTIVE' }]); await flush();
  assert.equal(ui.requests[2].url, 'projects/7');
  ui.requests[2].resolve({ id: 7, projectKey: 'QA', name: 'Quality', status: 'ACTIVE' }); await flush();
  assert.equal(ui.requests[3].url, 'projects/7/requirements/10');
  ui.requests[3].resolve(ui.requirement()); await flush();
  ui.requests[4].resolve([{ testCase: ui.caseRow(), status: 'REMOVED' }]); await flush();
  const openCase = ui.$('#asset-traces').find('a')[0];
  assert.match(openCase.attributes.href, /\/veriqra\/index.html\?projectId=7&testCaseId=20&sourceRequirementId=10#test-cases$/);
  await ui.fire(openCase, 'click'); await flush();
  assert.equal(ui.requests[5].url, 'projects/7/test-cases/20');
  ui.requests[5].resolve({ testCase: ui.caseRow(), steps: [] }); await flush();
  assert.equal(ui.route().sourceRequirementId, '10'); assert.equal(ui.route().testCaseId, '20');
  assert.equal(ui.requests.length, 6, 'Opening a case must not automatically scan reverse links.');
  const back = ui.$('#asset-primary-actions').find('a')[0]; await ui.fire(back, 'click'); await flush();
  assert.equal(ui.route().requirementId, '10'); assert.equal(ui.route().sourceTestCaseId, '20');
  assert.equal(ui.requests[6].url, 'projects/7/requirements/10');
});

test('actual app create route preserves source requirement through partial link retry', async () => {
  const ui = browser({ view: 'test-cases', projectId: '7', action: 'create', sourceRequirementId: '10' }, true);
  await ui.pageshow(); await flush(); ui.requests[0].resolve({ id: 1, username: 'tester', systemRole: 'USER' }); await flush();
  ui.requests[1].resolve([{ id: 7, projectKey: 'QA', name: 'Quality', status: 'ACTIVE' }]); await flush();
  ui.requests[2].resolve({ id: 7, projectKey: 'QA', name: 'Quality', status: 'ACTIVE' }); await flush();
  ui.$('#asset-title').val('Created case'); const saving = ui.submit('#asset-form');
  ui.requests[3].resolve(ui.caseRow()); await flush(); ui.requests[4].reject({ status: 500, message: 'Unavailable' }); await flush();
  ui.requests[5].resolve({ testCase: ui.caseRow(), steps: [] }); await saving;
  assert.equal(ui.route().testCaseId, '20'); assert.equal(ui.route().sourceRequirementId, '10'); assert.equal(ui.route().action, undefined);
  const retrying = ui.click('#asset-partial-link-retry');
  assert.equal(ui.requests[6].url, 'projects/7/requirements/10/test-cases/20');
  ui.requests[6].resolve(); await flush(); ui.requests[7].resolve({ testCase: ui.caseRow(), steps: [] }); await retrying;
  assert.equal(ui.$('#asset-partial-link-panel').hasClass('d-none'), true);
  assert.equal(ui.requests.filter(call => call.method === 'post' && call.url === 'projects/7/test-cases').length, 1);
});

test('actual app requirement round trip preserves plan and frozen-run origin', async () => {
  const ui = browser({ view: 'test-cases', projectId: '7', testCaseId: '20', sourcePlanId: '30', runId: '40', runCaseId: '50', attemptId: '60' }, true);
  await ui.pageshow(); await flush(); ui.requests[0].resolve({ id: 1, username: 'tester', systemRole: 'USER' }); await flush();
  ui.requests[1].resolve([{ id: 7, projectKey: 'QA', name: 'Quality', status: 'ACTIVE' }]); await flush();
  ui.requests[2].resolve({ id: 7, projectKey: 'QA', name: 'Quality', status: 'ACTIVE' }); await flush();
  ui.requests[3].resolve({ testCase: ui.caseRow(), steps: [] }); await flush();
  const loading = ui.click('#asset-trace-load'); ui.requests[4].resolve([ui.requirement()]); await flush();
  ui.requests[5].resolve([{ testCase: ui.caseRow(), status: 'CONFIRMED' }]); await loading;
  await ui.fire(ui.$('#asset-traces').find('a')[0], 'click'); await flush();
  assert.equal(ui.route().sourcePlanId, '30'); assert.equal(ui.route().runCaseId, '50'); assert.equal(ui.route().attemptId, '60');
  ui.requests[6].resolve(ui.requirement()); await flush(); ui.requests[7].resolve([]); await flush();
  const backCase = ui.$('#asset-primary-actions').find('a')[0]; await ui.fire(backCase, 'click'); await flush();
  assert.equal(ui.route().testCaseId, '20'); assert.equal(ui.route().sourcePlanId, '30'); assert.equal(ui.route().runId, '40');
  ui.requests[8].resolve({ testCase: ui.caseRow(), steps: [] }); await flush();
  const links = ui.$('#asset-primary-actions').find('a').get();
  assert.equal(links.some(link => /#test-plans$/.test(link.attributes.href) && /planId=30/.test(link.attributes.href)), true);
  assert.equal(links.some(link => /#runs$/.test(link.attributes.href) && /runCaseId=50/.test(link.attributes.href) && /attemptId=60/.test(link.attributes.href)), true);
});
