// Run with: node --test src/test/js/i18n.test.js
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const web = path.resolve(__dirname, '../../main/webapp');
const bootstrapSource = fs.readFileSync(path.join(web, 'assets/js/locale-bootstrap.js'), 'utf8');
const source = fs.readFileSync(path.join(web, 'assets/js/i18n.js'), 'utf8');
const catalog = name => JSON.parse(fs.readFileSync(path.join(web, 'i18n', name + '.json'), 'utf8'));

function page(options = {}) {
  const saved = new Map(options.saved ? [['veriqra.locale', options.saved]] : []);
  const base = options.base || 'http://127.0.0.1:9000/veriqra/';
  const requested = [];
  const listeners = new Map();
  const classes = new Set();
  const doc = {
    currentScript: { src: new URL('assets/js/i18n.js', base).href },
    baseURI: new URL('admin/users.html', base).href,
    documentElement: { lang: 'en', classList: {
      add: name => classes.add(name), remove: name => classes.delete(name), contains: name => classes.has(name)
    } },
    querySelectorAll: () => [],
    addEventListener: (name, listener) => listeners.set(name, listener),
    dispatchEvent: () => {}
  };
  const window = {
    navigator: { languages: options.languages || [options.language || 'en-US'], language: options.language || 'en-US' },
    localStorage: { getItem: key => saved.get(key) || null, setItem: (key, value) => saved.set(key, value) },
    fetch: async url => {
      requested.push(url);
      if (options.fail && url.endsWith(options.fail + '.json')) return { ok: false };
      return { ok: true, json: async () => catalog(url.endsWith('zh-CN.json') ? 'zh-CN' : 'en') };
    }
  };
  const context = { window, document: doc, URL, Date, Intl, Promise,
    CustomEvent: class { constructor(type, details) { this.type = type; this.detail = details.detail; } },
    console: { warn: () => {} } };
  vm.runInNewContext(bootstrapSource, context);
  if (!options.noI18n) vm.runInNewContext(source, context);
  return { i18n: window.I18n, doc, saved, requested, classes,
    fire: name => { if (listeners.has(name)) listeners.get(name)(); } };
}

test('head bootstrap resolves stored, browser, English and invalid preferences before content paints', () => {
  for (const [options, expected, pending] of [
    [{ saved: 'zh-CN', languages: ['en-US'] }, 'zh-CN', true],
    [{ languages: ['zh-SG', 'en-US'] }, 'zh-CN', true],
    [{ saved: 'en', languages: ['zh-CN'] }, 'en', false],
    [{ saved: 'invalid', languages: ['en-US'] }, 'en', false]
  ]) {
    const context = page(options);
    assert.equal(context.doc.documentElement.lang, expected);
    assert.equal(context.classes.has('i18n-pending'), pending);
  }
});

test('pending text is released after translation success, failure, or a missing i18n script', async () => {
  for (const options of [{ saved: 'zh-CN' }, { saved: 'zh-CN', fail: 'zh-CN' },
    { saved: 'zh-CN', noI18n: true }]) {
    const context = page(options);
    assert.equal(context.classes.has('i18n-pending'), true);
    context.fire('DOMContentLoaded');
    if (context.i18n) await context.i18n.init();
    await new Promise(setImmediate);
    assert.equal(context.classes.has('i18n-pending'), false);
    if (options.fail) assert.equal(context.i18n.getLocale(), 'en');
  }
});

test('default, saved and browser locales resolve with invalid saved fallback', async () => {
  for (const [options, expected] of [
    [{}, 'en'], [{ languages: ['zh-SG', 'en-US'] }, 'zh-CN'],
    [{ languages: ['zh-Hans-CN'] }, 'zh-CN'], [{ languages: ['fr-FR'] }, 'en'],
    [{ saved: 'en', languages: ['zh-CN'] }, 'en'],
    [{ saved: 'zh-CN', languages: ['en-US'] }, 'zh-CN'],
    [{ saved: 'invalid', languages: ['zh-CN'] }, 'zh-CN']
  ]) {
    const context = page(options);
    await context.i18n.init();
    assert.equal(context.i18n.getLocale(), expected);
    assert.equal(context.doc.documentElement.lang, expected);
  }
});

test('switch persists preference, interpolates safely and falls back to English', async () => {
  const context = page();
  await context.i18n.init();
  assert.equal(await context.i18n.setLocale('zh-CN'), true);
  assert.equal(context.saved.get('veriqra.locale'), 'zh-CN');
  assert.equal(context.doc.documentElement.lang, 'zh-CN');
  assert.equal(context.i18n.t('admin.reclaimConfirm', { amount: '500', username: '<alice>' }),
    '确定从 <alice> 收回 500 点额度吗？');
  assert.equal(context.i18n.t('missing.key', null, 'Readable fallback'), 'Readable fallback');
  assert.equal(await context.i18n.setLocale('invalid'), false);
  assert.equal(context.i18n.getLocale(), 'zh-CN');
});

test('status, error and BigInt credit formatting keep machine values intact', async () => {
  const context = page({ saved: 'zh-CN' });
  await context.i18n.init();
  assert.equal(context.i18n.enumLabel('NEEDS_REVIEW'), '待复核');
  assert.equal(context.i18n.enumLabel('PASS'), '通过');
  assert.equal(context.i18n.error({ code: 'LAST_ADMIN_REQUIRED' }), '系统必须至少保留一名启用状态的管理员。');
  assert.equal(context.i18n.formatCredit('9007199254740993').replace(/[^0-9]/g, ''), '9007199254740993');
  assert.equal(context.i18n.formatDateTime('2026-09-24T01:20:56.124168').includes('09:20:56'), true);
});

test('credit and handoff conflict codes render readable text in both languages', async () => {
  const context = page({ saved: 'zh-CN' });
  await context.i18n.init();
  for (const code of ['INSUFFICIENT_CREDIT', 'TRANSFER_SELF_NOT_ALLOWED', 'HANDOFF_NOT_ALLOWED',
    'HANDOFF_ALREADY_RESOLVED', 'HANDOFF_PENDING_EXISTS', 'TASK_REWARD_LOCKED']) {
    const chinese = context.i18n.error({ code, message: 'Request conflicts with current state' });
    assert.notEqual(chinese, code);
    assert.notEqual(chinese, 'Request conflicts with current state');
    await context.i18n.setLocale('en');
    const english = context.i18n.error({ code, message: 'Request conflicts with current state' });
    assert.notEqual(english, code);
    assert.notEqual(english, 'Request conflicts with current state');
    await context.i18n.setLocale('zh-CN');
  }
});

test('UTC timestamps render in Asia/Shanghai in both locales, including the next calendar day', async () => {
  const context = page({ saved: 'en' });
  await context.i18n.init();
  assert.match(context.i18n.formatDateTime('2026-09-25T11:53:00Z'), /19:53:00/);
  assert.match(context.i18n.formatDateTime('2026-09-25T11:53:00'), /19:53:00/);
  assert.match(context.i18n.formatDateTime('2026-09-25T17:00:00Z'), /26.*01:00:00/);
  assert.match(context.i18n.formatDate('2026-09-25T17:00:00Z'), /26/);
  assert.equal(context.i18n.toUtcFilter('2026-09-26T01:00'), '2026-09-25T17:00:00');
  await context.i18n.setLocale('zh-CN');
  assert.match(context.i18n.formatDateTime('2026-09-25T11:53:00Z'), /19:53:00/);
  assert.match(context.i18n.formatDateTime('2026-09-25T17:00:00'), /26.*01:00:00/);
  assert.equal(context.i18n.toUtcFilter('2026-09-26T01:00'), '2026-09-25T17:00:00');
  assert.equal(context.i18n.t('system.displayTimeZone'), 'Asia/Shanghai（UTC+8）');
});

test('login Retry-After renders a locale-specific duration without treating seconds as a timestamp', async () => {
  const context = page({ saved: 'en' });
  await context.i18n.init();
  assert.equal(context.i18n.formatRetryDelay(287), '4 min 47 sec');
  await context.i18n.setLocale('zh-CN');
  assert.equal(context.i18n.formatRetryDelay(287), '4 分 47 秒');
  assert.equal(context.i18n.formatRetryDelay(45), '45 秒');
  assert.equal(context.i18n.formatRetryDelay(null), null);
});

test('resource paths are context-safe and language-pack failure leaves readable English', async () => {
  for (const base of ['http://127.0.0.1:9000/', 'http://127.0.0.1:9000/veriqra/']) {
    const context = page({ base, saved: 'zh-CN', fail: 'zh-CN' });
    await context.i18n.init();
    assert.equal(context.i18n.getLocale(), 'en');
    assert.equal(context.i18n.t('common.save'), 'Save');
    assert.equal(context.i18n.resourceUrl('zh-CN'), new URL('i18n/zh-CN.json', base).href);
    assert.equal(context.requested[0], new URL('i18n/en.json', base).href);
  }
});

test('English and Chinese catalogs have matching keys and interpolation placeholders', () => {
  const en = catalog('en'), zh = catalog('zh-CN');
  assert.deepEqual(Object.keys(en).sort(), Object.keys(zh).sort());
  const placeholders = value => [...value.matchAll(/\{([A-Za-z][A-Za-z0-9]*)\}/g)].map(match => match[1]).sort();
  for (const key of Object.keys(en)) assert.deepEqual(placeholders(en[key]), placeholders(zh[key]), key);
});
