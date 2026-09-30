const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const source = fs.readFileSync(path.resolve(__dirname, '../../main/webapp/assets/js/collaboration.js'), 'utf8');

function deferred() {
  let settled, value;
  const success = [], failure = [], completion = [];
  const promise = {
    done(fn) { if (settled === 'ok') fn(value); else success.push(fn); return promise; },
    fail(fn) { if (settled === 'error') fn(value); else failure.push(fn); return promise; },
    always(fn) { if (settled) fn(); else completion.push(fn); return promise; },
    resolve(data) { settled = 'ok'; value = data; success.forEach(fn => fn(data)); completion.forEach(fn => fn()); },
    reject(error) { settled = 'error'; value = error; failure.forEach(fn => fn(error)); completion.forEach(fn => fn()); }
  };
  return promise;
}

function browser(user = { id: 2, systemRole: 'USER' }) {
  const nodes = new Map(), calls = [], listeners = new Map();
  class Node {
    constructor() { this.value = ''; this.content = ''; this.children = []; this.handlers = {}; this.hidden = false; this.disabled = false; this.dataset = {}; }
    reset() { this.value = ''; }
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
    attr(name, value) { this[name] = value; return this; }
  }
  function $(selector) {
    if (typeof selector !== 'string') return selector;
    if (selector.startsWith('<')) return new Node();
    if (selector.includes(',')) {
      const group = selector.split(',').map(key => $(key.trim()));
      return { on(events, fn) { group.forEach(node => node.on(events, fn)); return this; },
        val(value) { group.forEach(node => node.val(value)); return this; },
        text(value) { group.forEach(node => node.text(value)); return this; },
        empty() { group.forEach(node => node.empty()); return this; },
        addClass(name) { group.forEach(node => node.addClass(name)); return this; },
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
  const api = {
    get(url) { const call = deferred(); calls.push({ url, call }); return call; },
    post(url, body) { const call = deferred(); calls.push({ url, body, call }); return call; }
  };
  const window = { VeriqraApi: api, I18n: {
    t: (key, values, fallback) => (fallback || key).replace(/\{(\w+)\}/g, (_, name) => values?.[name] ?? ''),
    formatCredit: value => String(value), formatDateTime: value => String(value)
  } };
  const document = { addEventListener(name, callback) { listeners.set(name, callback); } };
  vm.runInNewContext(source, { window, document, jQuery: $, URLSearchParams, Date, Intl, Number, BigInt, crypto: global.crypto });
  function respondTasks(start, items, total, members = [], managers = [], teams = []) {
    const batch = calls.slice(start, start + 4);
    assert.equal(batch.length, 4);
    batch[0].call.resolve(managers); batch[1].call.resolve(members); batch[2].call.resolve(teams);
    batch[3].call.resolve({ items, total, page: 1, pageSize: 25 });
  }
  function task(id) { return { id, title: 'Task ' + id, teamId: 1, status: 'OPEN', assigneeUserId: 2, rewardCredit: '7' }; }
  listeners.get('veriqra:project')({ detail: { project: { id: 12, status: 'ACTIVE' } } });
  calls[0].call.resolve(user);
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

test('an ADMIN without project membership gets setup guidance, not manager powers or global credit errors', () => {
  const ui = browser({ id: 2, systemRole: 'ADMIN' });
  ui.respondTasks(1, [], 0);
  assert.equal(ui.$('#collab-appoint-form button').disabled, true);
  assert.equal(ui.$('#collab-team-form').hidden, true);
  assert.equal(ui.$('#collab-transfer-form').hidden, true);
  assert.equal(ui.$('#collab-setup').hidden, false);
  const steps = ui.$('#collab-setup-steps').children;
  assert.match(steps[0].children[0].content, /collab.nextStep/);
  assert.match(steps[1].children[0].content, /collab.prerequisitesFirst/);
  assert.match(ui.$('#collab-identity').content, /collab.platformAdmin.*collab.notMember/);
  assert.equal(ui.calls.some(c => /contributions|handoffs/.test(c.url)), false);
  assert.equal(ui.$('#collab-contribution-notice').content, 'collab.memberRequired');
  assert.equal(ui.$('#collab-feedback').content, '');
  ui.$('#collab-area-people').handlers.click();
  assert.equal(ui.$('#collab-pane-people').hidden, false);
  assert.equal(ui.$('#collab-pane-tasks').hidden, true);
  assert.equal(ui.$('#collab-area-people')['aria-pressed'], 'true');
});

test('active membership plus an appointment unlocks team creation; membership alone does not', () => {
  for (const appointed of [false, true]) {
    const ui = browser();
    const member = { userId: 2, username: 'tester', displayName: 'Tester', projectRole: 'TESTER', status: 'ACTIVE', userStatus: 'ACTIVE' };
    ui.respondTasks(1, [], 0, [member], appointed ? [{userId: 2, status: 'ACTIVE'}] : []);
    assert.equal(ui.$('#collab-team-form').hidden, !appointed);
    assert.equal(ui.$('#collab-invite-form').hidden, !appointed);
    const contribution = ui.calls.find(c => c.url.includes('contributions'));
    contribution.call.reject({status: 403, message: 'Denied'});
    assert.equal(ui.$('#collab-contribution-notice').content, 'collab.localDenied');
    assert.equal(ui.$('#collab-feedback').content, '');
  }
});

test('project change removes prior credit/contribution contents and ordinary user cannot see appointment form', () => {
  const ui = browser();
  ui.respondTasks(1, [], 0);
  ui.$('#collab-credit-history').append('old ledger');
  ui.$('#collab-contribution-list').append('old contribution');
  ui.listeners.get('veriqra:project')({detail:{project:{id:33,status:'ACTIVE'}}});
  assert.equal(ui.$('#collab-credit-history').children.length, 0);
  assert.equal(ui.$('#collab-contribution-list').children.length, 0);
  ui.calls.at(-1).call.resolve({id:2,systemRole:'USER'});
  ui.respondTasks(ui.calls.length - 4, [], 0);
  assert.equal(ui.$('#collab-appoint-form').hidden, true);
  assert.equal(ui.$('#collab-manager-help').content, 'collab.managerPermission');
});

test('returning from archived to active project recomputes form availability', () => {
  const ui = browser({id:2,systemRole:'ADMIN'});
  const member = {userId:2,projectRole:'TESTER',username:'admin',displayName:'Admin',status:'ACTIVE',userStatus:'ACTIVE'};
  ui.listeners.get('veriqra:project')({detail:{project:{id:33,status:'ARCHIVED'}}});
  ui.calls.at(-1).call.resolve({id:2,systemRole:'ADMIN'});
  ui.respondTasks(ui.calls.length - 4, [], 0, [member]);
  assert.equal(ui.$('#collab-appoint-form button').disabled, true);
  ui.listeners.get('veriqra:project')({detail:{project:{id:12,status:'ACTIVE'}}});
  ui.calls.at(-1).call.resolve({id:2,systemRole:'ADMIN'});
  ui.respondTasks(ui.calls.length - 4, [], 0, [member]);
  assert.equal(ui.$('#collab-appoint-form button').disabled, false);
});

test('a historical team lead without active project membership cannot see task creation', () => {
  const ui = browser({id:2,systemRole:'ADMIN'});
  ui.respondTasks(1, [], 0, [], [], [{id:1,name:'Old team',status:'ACTIVE',leadUserId:2}]);
  assert.equal(ui.$('#collab-task-form').hidden, true);
  assert.equal(ui.$('#collab-task-help').content, 'collab.taskPermission');
});

test('setup covers all five actual prerequisite states with only one next step', () => {
  const members = [2, 3].map(userId => ({userId, username:'user'+userId, displayName:'User '+userId,
    projectRole:'TESTER',status:'ACTIVE',userStatus:'ACTIVE'}));
  const team = {id:1,name:'Team',status:'ACTIVE',leadUserId:2};
  for (let stage = 0; stage <= 5; stage++) {
    const ui = browser();
    ui.respondTasks(1, stage === 5 ? [ui.task(1)] : [], stage === 5 ? 1 : 0,
      stage >= 1 ? members : [], stage >= 2 ? [{userId:2,status:'ACTIVE'}] : [], stage >= 3 ? [team] : []);
    ui.calls.filter(c => c.url.endsWith('teams/1/members')).forEach(c => c.call.resolve(
      stage >= 4 ? [{teamId:1,userId:2,status:'ACTIVE'},{teamId:1,userId:3,status:'ACTIVE'}] : [{teamId:1,userId:2,status:'ACTIVE'}]));
    const labels = ui.$('#collab-setup-steps').children.map(row => row.children[0].content);
    assert.equal(labels.filter(s => s.includes('collab.completed')).length, stage);
    assert.equal(labels.filter(s => s.includes('collab.nextStep')).length, stage === 5 ? 0 : 1);
    if (stage < 5) assert.match(labels[stage], /collab.nextStep/);
    assert.equal(ui.$('#collab-setup').hidden, false);
  }
});

test('late team success and failure cannot replace selected team or contaminate tasks', () => {
  const ui = browser();
  const teams = [1,2].map(id => ({id,name:'Team '+id,status:'ACTIVE',leadUserId:2}));
  ui.respondTasks(1, [], 0, [], [], teams);
  const rows = ui.$('#collab-teams').children;
  rows[0].handlers.click(); const a = ui.calls.at(-1);
  rows[1].handlers.click(); const b = ui.calls.at(-1);
  b.call.resolve([]); a.call.resolve([]);
  assert.match(ui.$('#collab-team-title').content, /^Team 2/);
  rows[0].handlers.click(); const staleError = ui.calls.at(-1);
  rows[1].handlers.click(); ui.calls.at(-1).call.resolve([]);
  staleError.call.reject({status:500,message:'old team error'});
  assert.equal(ui.$('#collab-teams-feedback').content, '');
  assert.equal(ui.$('#collab-tasks-feedback').content, '');
});

test('core read error stays in owning area after navigation', () => {
  const ui = browser();
  ui.$('#collab-area-tasks').handlers.click();
  ui.calls[1].call.reject({status:403,message:'Denied manager read'});
  assert.equal(ui.$('#collab-people-feedback').content, 'collab.localDenied');
  assert.equal(ui.$('#collab-tasks-feedback').content, '');
  assert.equal(ui.$('#collab-feedback').content, '');
});

test('write response after project switch cannot reset new project forms or selection', () => {
  const ui = browser({id:2,systemRole:'ADMIN'});
  ui.respondTasks(1, [], 0);
  ui.$('#collab-area-people').handlers.click();
  const form = ui.$('#collab-invite-form');
  ui.$('#collab-invite-username').val('old-user');
  form.handlers.submit.call(form, {preventDefault(){}});
  const oldWrite = ui.calls.at(-1);
  ui.listeners.get('veriqra:project')({detail:{project:{id:33,status:'ACTIVE'}}});
  const before = ui.calls.length;
  form.val('new project draft');
  oldWrite.call.resolve({});
  assert.equal(ui.calls.length, before);
  assert.equal(form.val(), 'new project draft');
  assert.equal(ui.$('#collab-people-feedback').content, '');
});

test('write error remains in original area when user changes sections', () => {
  const ui = browser({id:2,systemRole:'ADMIN'});
  ui.respondTasks(1, [], 0);
  ui.$('#collab-area-people').handlers.click();
  const form = ui.$('#collab-invite-form');
  ui.$('#collab-invite-username').val('missing-user');
  form.handlers.submit.call(form, {preventDefault(){}});
  const write = ui.calls.at(-1);
  ui.$('#collab-area-tasks').handlers.click();
  write.call.reject({status:404,message:'No such user'});
  assert.equal(ui.$('#collab-people-feedback').content, 'No such user');
  assert.equal(ui.$('#collab-tasks-feedback').content, '');
});
