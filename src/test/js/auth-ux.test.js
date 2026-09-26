// Run with: node --test src/test/js/*.test.js
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const web = path.resolve(__dirname, '../../main/webapp');
const source = name => fs.readFileSync(path.join(web, 'assets/js', name), 'utf8');
const catalogs = Object.fromEntries(['en', 'zh-CN'].map(locale =>
  [locale, JSON.parse(fs.readFileSync(path.join(web, 'i18n', locale + '.json'), 'utf8'))]));

function deferred() {
  let settled, value;
  const done = [], fail = [], always = [];
  const call = (callbacks, arg) => callbacks.forEach(callback => callback(arg));
  const result = {
    done(callback) { if (settled === 'resolved') callback(value); else done.push(callback); return result; },
    fail(callback) { if (settled === 'rejected') callback(value); else fail.push(callback); return result; },
    always(callback) { if (settled) callback(value); else always.push(callback); return result; },
    resolve(arg) { settled = 'resolved'; value = arg; call(done, arg); call(always, arg); },
    reject(arg) { settled = 'rejected'; value = arg; call(fail, arg); call(always, arg); },
    promise() { return result; }
  };
  return result;
}

function page(locale) {
  const nodes = new Map(), requests = [], navigation = [];
  class Node {
    constructor() { this.value = ''; this.content = ''; this.hidden = true; this.handlers = {}; this.children = new Map(); }
    on(name, callback) { this.handlers[name] = callback; return this; }
    text(value) { if (value === undefined) return this.content; this.content = value; return this; }
    toggleClass(name, value) { if (name === 'd-none') this.hidden = value; return this; }
    prop() { return this; }
    find(name) { if (!this.children.has(name)) this.children.set(name, new Node()); return this.children.get(name); }
    val(value) { if (value === undefined) return this.value; this.value = value; return this; }
  }
  function $(selector) { if (!nodes.has(selector)) nodes.set(selector, new Node()); return nodes.get(selector); }
  $.Deferred = deferred;
  $.ajax = settings => { const call = deferred(); requests.push({ settings, call }); return call; };
  const t = (key, params, fallback) => (catalogs[locale][key] || fallback || key)
    .replace(/\{(\w+)\}/g, (match, name) => params?.[name] ?? match);
  const window = {
    location: { pathname: '/login.html', replace: target => navigation.push(target) },
    I18n: { init: async () => {}, t, error: error => error.message, formatRetryDelay: seconds => seconds === 287
      ? t('auth.retryMinutesSeconds', { minutes: 4, seconds: 47 }) : null }
  };
  const document = { baseURI: 'http://localhost:8080/veriqra/login.html', addEventListener: () => {} };
  const context = { window, document, jQuery: $, URL, Number };
  vm.runInNewContext(source('api.js'), context);
  vm.runInNewContext(source('login.js'), context);
  return { nodes, requests, navigation, $ };
}

function failure(status, retryAfter) {
  return { status, responseJSON: { error: { code: status === 429 ? 'TOO_MANY_REQUESTS' : 'UNAUTHENTICATED' } },
    getResponseHeader: name => name === 'Retry-After' ? retryAfter : null };
}

test('unauthenticated auth/me is a normal login page, including a second visit', async () => {
  for (let visit = 0; visit < 2; visit++) {
    const browser = page('zh-CN');
    await Promise.resolve();
    assert.equal(browser.requests[0].settings.url, 'http://localhost:8080/veriqra/api/auth/me');
    browser.requests[0].call.reject(failure(401));
    assert.equal(browser.nodes.get('#login-error').content, '');
    assert.equal(browser.nodes.get('#login-error').hidden, true);
    assert.deepEqual(browser.navigation, []);
  }
});

test('wrong password and login rate limit are distinct, localized, and preserve Retry-After', async () => {
  for (const [locale, invalid, limited] of [
    ['en', 'Invalid username or password.', 'Too many login attempts. Please try again in 4 min 47 sec.'],
    ['zh-CN', '用户名或密码错误。', '登录尝试次数过多，请在 4 分 47 秒后重试。']
  ]) {
    const browser = page(locale);
    await Promise.resolve();
    browser.requests[0].call.reject(failure(401));
    browser.$('#username').val('test-user');
    browser.$('#password').val('wrong-password');
    const submit = () => browser.nodes.get('#login-form').handlers.submit({ preventDefault() {} });
    submit();
    browser.requests[1].call.reject(failure(401));
    assert.equal(browser.nodes.get('#login-error').content, invalid);
    submit();
    browser.requests[2].call.reject(failure(429, '287'));
    assert.equal(browser.nodes.get('#login-error').content, limited);
    assert.equal(browser.nodes.get('#login-error').hidden, false);
  }
});
