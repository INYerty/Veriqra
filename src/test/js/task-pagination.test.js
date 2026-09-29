const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const source = fs.readFileSync(path.resolve(__dirname, '../../main/webapp/assets/js/collaboration.js'), 'utf8');

function deferred() {
  let settled, value;
  const success = [], failure = [];
  const promise = {
    done(fn) { if (settled === 'ok') fn(value); else success.push(fn); return promise; },
    fail(fn) { if (settled === 'error') fn(value); else failure.push(fn); return promise; },
    resolve(data) { settled = 'ok'; value = data; success.forEach(fn => fn(data)); },
    reject(error) { settled = 'error'; value = error; failure.forEach(fn => fn(error)); }
  };
  return promise;
}

function browser() {
  const nodes = new Map(), calls = [], listeners = new Map();
  class Node {
    constructor() { this.value = ''; this.content = ''; this.children = []; this.handlers = {}; this.hidden = false; this.disabled = false; }
    on(events, fn) { events.split(' ').forEach(event => { this.handlers[event] = fn; }); return this; }
    text(value) { if (value === undefined) return this.content; this.content = value; return this; }
    val(value) { if (value === undefined) return this.value; this.value = value; return this; }
    empty() { this.children = []; return this; }
    append(...children) { this.children.push(...children); return this; }
    appendTo(parent) { parent.append(this); return this; }
    toggleClass(name, hidden) { if (name === 'd-none') this.hidden = hidden; return this; }
    addClass(name) { if (name === 'd-none') this.hidden = true; return this; }
    removeClass(name) { if (name === 'd-none') this.hidden = false; return this; }
    prop(name, value) { if (name === 'disabled') this.disabled = value; return this; }
    find() { return this; }
    attr() { return this; }
  }
  function $(selector) {
    if (typeof selector !== 'string' || selector.startsWith('<')) return new Node();
    if (selector.includes(',')) {
      const group = selector.split(',').map(key => $(key.trim()));
      return { on(events, fn) { group.forEach(node => node.on(events, fn)); return this; },
        val(value) { group.forEach(node => node.val(value)); return this; },
        prop(name, value) { group.forEach(node => node.prop(name, value)); return this; } };
    }
    if (!nodes.has(selector)) nodes.set(selector, new Node());
    return nodes.get(selector);
  }
  $.when = (...promises) => {
    const joined = deferred(), values = new Array(promises.length);
    let remaining = promises.length;
    promises.forEach((promise, index) => promise.done(value => {
      values[index] = value;
      if (--remaining === 0) joined.resolve(values);
    }).fail(error => joined.reject(error)));
    return { done(fn) { joined.done(values => fn(...values)); return this; }, fail(fn) { joined.fail(fn); return this; } };
  };
  const api = { get(url) { const call = deferred(); calls.push({ url, call }); return call; } };
  const window = { VeriqraApi: api, I18n: {
    t: (key, values, fallback) => (fallback || key).replace(/\{(\w+)\}/g, (_, name) => values?.[name] ?? ''),
    formatCredit: value => String(value), formatDateTime: value => String(value)
  } };
  const document = { addEventListener(name, callback) { listeners.set(name, callback); } };
  vm.runInNewContext(source, { window, document, jQuery: $, URLSearchParams, Date, Intl, Number, BigInt, crypto: global.crypto });
  function respondTasks(start, items, total) {
    const batch = calls.slice(start, start + 4);
    assert.equal(batch.length, 4);
    batch[0].call.resolve([]); batch[1].call.resolve([]); batch[2].call.resolve([]);
    batch[3].call.resolve({ items, total, page: 1, pageSize: 25 });
  }
  function task(id) { return { id, title: 'Task ' + id, teamId: 1, status: 'OPEN', assigneeUserId: 2, rewardCredit: '7' }; }
  listeners.get('veriqra:project')({ detail: { project: { id: 12 } } });
  calls[0].call.resolve({ id: 2, systemRole: 'USER' });
  return { $, nodes, calls, listeners, respondTasks, task };
}

test('pages render 25/25/10 with reward, previous/next and reset on filter', () => {
  const ui = browser();
  ui.respondTasks(1, Array.from({ length: 25 }, (_, i) => ui.task(60 - i)), 60);
  assert.equal(ui.nodes.get('#collab-task-page-info').content, 'Page 1 of 3 · Total 60');
  assert.equal(ui.nodes.get('#collab-tasks').children.length, 25);
  assert.match(ui.nodes.get('#collab-tasks').children[0].content, /Reward Credit: 7/);
  ui.$('#collab-task-next').handlers.click();
  assert.match(ui.calls.at(-1).url, /tasks\?page=2&pageSize=25/);
  const second = ui.calls.length - 4;
  ui.respondTasks(second, Array.from({ length: 25 }, (_, i) => ui.task(35 - i)), 60);
  assert.equal(ui.nodes.get('#collab-task-page-info').content, 'Page 2 of 3 · Total 60');
  ui.$('#collab-task-next').handlers.click();
  ui.respondTasks(ui.calls.length - 4, Array.from({ length: 10 }, (_, i) => ui.task(10 - i)), 60);
  assert.equal(ui.nodes.get('#collab-task-page-info').content, 'Page 3 of 3 · Total 60');
  assert.equal(ui.nodes.get('#collab-tasks').children.length, 10);
  assert.equal(ui.$('#collab-task-next').disabled, true);
  ui.$('#collab-task-prev').handlers.click();
  assert.match(ui.calls.at(-1).url, /tasks\?page=2&pageSize=25/);
  ui.respondTasks(ui.calls.length - 4, Array.from({ length: 25 }, (_, i) => ui.task(35 - i)), 60);
  ui.$('#collab-filter-status').val('SUBMITTED');
  ui.$('#collab-filter-status').handlers.change();
  assert.match(ui.calls.at(-1).url, /tasks\?page=1&pageSize=25&status=SUBMITTED/);
  ui.respondTasks(ui.calls.length - 4, [], 0);
  assert.equal(ui.nodes.get('#collab-task-page-info').content, 'Page 1 of 1 · Total 0');
});

test('late page and task detail responses cannot replace newer filtered state', () => {
  const ui = browser();
  ui.respondTasks(1, Array.from({ length: 25 }, (_, i) => ui.task(60 - i)), 60);
  const oldRow = ui.nodes.get('#collab-tasks').children[0];
  oldRow.handlers.click();
  const detail = ui.calls.at(-1);
  ui.$('#collab-task-next').handlers.click();
  const stalePage = ui.calls.length - 4;
  oldRow.handlers.click(); // A stale row cannot open another task while page data is loading.
  assert.equal(ui.calls.at(-1).url.includes('page=2'), true);
  ui.$('#collab-filter-status').val('OPEN');
  ui.$('#collab-filter-status').handlers.change();
  const filteredPage = ui.calls.length - 4;
  ui.respondTasks(filteredPage, [ui.task(60)], 1);
  detail.call.resolve({ task: ui.task(60), events: [] });
  ui.respondTasks(stalePage, Array.from({ length: 25 }, (_, i) => ui.task(35 - i)), 60);
  assert.equal(ui.nodes.get('#collab-task-page-info').content, 'Page 1 of 1 · Total 1');
  assert.equal(ui.nodes.get('#collab-tasks').children.length, 1);
  assert.equal(ui.$('#collab-task-detail').hidden, true);
});

test('rapid Task A to Task B selection keeps B detail and project switch resets filters', () => {
  const ui = browser();
  ui.respondTasks(1, [ui.task(60), ui.task(59)], 2);
  const rows = ui.nodes.get('#collab-tasks').children;
  rows[0].handlers.click(); const a = ui.calls.at(-1);
  rows[1].handlers.click(); const b = ui.calls.at(-1);
  b.call.resolve({ task: ui.task(59), events: [] });
  a.call.resolve({ task: ui.task(60), events: [] });
  assert.equal(ui.$('#collab-task-detail-title').content, '#59 · Task 59');
  ui.$('#collab-filter-status').val('OPEN');
  ui.listeners.get('veriqra:project')({ detail: { project: { id: 33 } } });
  assert.equal(ui.$('#collab-task-detail').hidden, true);
  assert.equal(ui.$('#collab-filter-status').val(), '');
  ui.calls.at(-1).call.resolve({ id: 2, systemRole: 'USER' });
  assert.match(ui.calls.at(-1).url, /projects\/33\/tasks\?page=1&pageSize=25$/);
});
