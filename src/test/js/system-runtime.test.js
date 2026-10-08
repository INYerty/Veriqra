const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const web = path.resolve(__dirname, '../../main/webapp');
const source = fs.readFileSync(path.join(web, 'admin/admin.js'), 'utf8');
const catalog = locale => JSON.parse(fs.readFileSync(path.join(web, 'i18n', locale + '.json'), 'utf8'));

async function systemPage(locale) {
  const nodes = new Map(), strings = catalog(locale);
  class Node {
    constructor() { this.children = []; this.content = ''; }
    data(name) { return name === 'admin-page' ? 'system' : null; }
    text(value) { if (value === undefined) return this.content; this.content = String(value); return this; }
    append(...items) { this.children.push(...items); return this; }
    empty() { this.children = []; return this; }
    toggleClass() { return this; }
    addClass() { return this; }
    attr() { return this; }
    on() { return this; }
  }
  function $(selector) { if (selector.startsWith('<')) return new Node();
    if (!nodes.has(selector)) nodes.set(selector, new Node()); return nodes.get(selector); }
  const runtime = {
    veriqraVersion: '0.1.0-SNAPSHOT', buildCommit: 'a'.repeat(40), buildTime: '2026-09-29T03:12:43Z',
    applicationStatus: 'UP', databaseStatus: 'UP', databaseVersion: '8.0.46', databaseTableCount: 32,
    serverTime: '2026-09-29T03:13:00Z', applicationTimeZone: 'Asia/Shanghai (UTC+8)'
  };
  const window = { I18n: {
    init: () => Promise.resolve(), t: (key, params, fallback) => strings[key] || fallback || key,
    enumLabel: value => value, formatNumber: value => String(value),
    formatDateTime: value => 'Shanghai:' + value
  }, VeriqraAccessLogFormat: { operatingSystem: value => value } };
  const api = { get: url => url === 'auth/me'
    ? Promise.resolve({ username: 'admin', systemRole: 'ADMIN' })
    : Promise.resolve(runtime) };
  window.VeriqraApi = api;
  vm.runInNewContext(source, { window, document: { addEventListener() {} },
    jQuery: $, URL, location: { search: '' }, bootstrap: { Modal: {} } });
  await new Promise(setImmediate);
  const pairs = new Map();
  function visit(node) {
    if (!node || !Array.isArray(node.children)) return;
    if (node.children.length === 2 && node.children[0]?.content && node.children[1]?.content)
      pairs.set(node.children[0].content, node.children[1].content);
    node.children.forEach(visit);
  }
  visit(nodes.get('#admin-content'));
  return pairs;
}

test('System renders allowlisted build and DB fields with localized labels and Shanghai time', async () => {
  for (const [locale, commitLabel, tablesLabel] of [
    ['en', 'Build commit', 'Database tables'], ['zh-CN', '构建提交', '数据库表数量']
  ]) {
    const values = await systemPage(locale);
    assert.equal(values.get(commitLabel), 'a'.repeat(40));
    assert.equal(values.get(tablesLabel), '32');
    assert.ok([...values.values()].some(value => value === 'Shanghai:2026-09-29T03:12:43Z'));
    assert.ok([...values.values()].some(value => value === 'Shanghai:2026-09-29T03:13:00Z'));
    assert.ok([...values.values()].some(value => value.includes('Asia/Shanghai')));
    assert.equal(values.size, 9);
  }
});
