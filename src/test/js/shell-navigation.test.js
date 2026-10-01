const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const source = fs.readFileSync(path.resolve(__dirname, '../../main/webapp/assets/js/shell.js'), 'utf8');

function browser({ width = 375, readyState = 'complete', sidebarId = 'admin-sidebar' } = {}) {
  class EventTarget {
    constructor() { this.listeners = new Map(); }
    addEventListener(type, listener) {
      if (!this.listeners.has(type)) this.listeners.set(type, []);
      this.listeners.get(type).push(listener);
    }
    dispatch(type, event) { (this.listeners.get(type) || []).forEach(listener => listener(event)); }
  }
  const document = new EventTarget();
  class Element extends EventTarget {
    constructor(tagName, attributes = {}) {
      super(); this.tagName = tagName; this.attributes = new Map(); this.children = []; this.parent = null;
      this.classes = new Set(); this.focusCount = 0;
      this.classList = {
        add: name => this.classes.add(name), remove: name => this.classes.delete(name),
        contains: name => this.classes.has(name),
        toggle: (name, force) => {
          const add = force === undefined ? !this.classes.has(name) : force;
          if (add) this.classes.add(name); else this.classes.delete(name);
          return add;
        }
      };
      Object.entries(attributes).forEach(([name, value]) => this.setAttribute(name, value));
    }
    set className(value) { this.classes = new Set(String(value).split(/\s+/).filter(Boolean)); }
    get className() { return [...this.classes].join(' '); }
    get id() { return this.getAttribute('id'); }
    setAttribute(name, value) {
      if (name === 'class') this.className = value; else this.attributes.set(name, String(value));
    }
    getAttribute(name) { return name === 'class' ? this.className : this.attributes.get(name) ?? null; }
    append(child) { child.parent = this; this.children.push(child); return child; }
    after(child) {
      child.parent = this.parent;
      this.parent.children.splice(this.parent.children.indexOf(this) + 1, 0, child);
    }
    contains(target) { for (let current = target; current; current = current.parent) if (current === this) return true; return false; }
    closest(selector) {
      for (let current = this; current; current = current.parent) {
        if (selector === '[aria-controls]' && current.getAttribute('aria-controls') !== null) return current;
        if (selector === '.sidebar a' && current.tagName === 'a') {
          for (let ancestor = current.parent; ancestor; ancestor = ancestor.parent)
            if (ancestor.classList.contains('sidebar')) return current;
        }
      }
      return null;
    }
    querySelector(selector) {
      assert.equal(selector, 'a, button');
      return descendants(this).find(element => element.tagName === 'a' || element.tagName === 'button') || null;
    }
    focus() { this.focusCount++; document.activeElement = this; document.dispatch('focusin', { target: this }); }
  }
  function descendants(element) { return element.children.flatMap(child => [child, ...descendants(child)]); }
  document.readyState = readyState;
  document.body = new Element('body');
  document.createElement = tagName => new Element(tagName);
  document.querySelectorAll = selector => {
    assert.equal(selector, '.sidebar');
    return descendants(document.body).filter(element => element.classList.contains('sidebar'));
  };
  document.querySelector = selector => {
    const match = /^\[aria-controls="(.+)"\]$/.exec(selector);
    assert.ok(match, 'Drawer helper only queries its existing toggle');
    return descendants(document.body).find(element => element.getAttribute('aria-controls') === match[1]) || null;
  };
  const window = new EventTarget(); window.innerWidth = width;
  window.I18n = { t: key => key === 'common.close' ? 'Close navigation' : key };
  const businessCalls = [];
  window.fetch = (...args) => businessCalls.push(['fetch', ...args]);
  window.VeriqraApi = new Proxy({}, { get: (_, key) => (...args) => businessCalls.push([key, ...args]) });
  const header = document.body.append(new Element('header'));
  const toggle = header.append(new Element('button', { id: 'existing-menu', 'aria-controls': sidebarId, 'aria-expanded': 'false' }));
  const icon = toggle.append(new Element('span'));
  const region = document.body.append(new Element('div'));
  const sidebar = region.append(new Element('nav', { id: sidebarId, class: 'sidebar' }));
  const nav = sidebar.append(new Element('a', { href: 'admin/users.html', 'data-admin-nav': 'users' }));
  const secondNav = sidebar.append(new Element('a', { href: 'index.html' }));
  const outside = region.append(new Element('button', { id: 'existing-content-action' }));
  let toggleCalls = 0, navigationCalls = 0;
  toggle.addEventListener('click', () => {
    toggleCalls++;
    toggle.setAttribute('aria-expanded', String(sidebar.classList.toggle('open')));
  });
  nav.addEventListener('click', () => { navigationCalls++; });
  vm.runInNewContext(source, { document, window });
  function click(target) {
    const event = { target, defaultPrevented: false, preventDefault() { this.defaultPrevented = true; } };
    for (let current = target; current; current = current.parent) current.dispatch('click', event);
    document.dispatch('click', event);
    return event;
  }
  function key(key) {
    const event = { key, defaultPrevented: false, preventDefault() { this.defaultPrevented = true; } };
    document.dispatch('keydown', event); return event;
  }
  return { document, window, toggle, icon, sidebar, nav, secondNav, outside, click, key, businessCalls,
    backdrop: () => region.children.find(element => element.classList.contains('vq-drawer-backdrop')),
    resize: nextWidth => { window.innerWidth = nextWidth; window.dispatch('resize', {}); },
    toggleCalls: () => toggleCalls, navigationCalls: () => navigationCalls };
}

test('Drawer setup waits for DOM readiness and adds a localized non-tabstop backdrop', () => {
  const ui = browser({ readyState: 'loading' });
  assert.equal(ui.backdrop(), undefined);
  ui.document.dispatch('DOMContentLoaded', {});
  assert.equal(ui.backdrop().type, 'button');
  assert.equal(ui.backdrop().tabIndex, -1);
  assert.equal(ui.backdrop().getAttribute('aria-label'), 'Close navigation');
  assert.equal(ui.backdrop().getAttribute('data-i18n-aria-label'), 'common.close');
});

test('Existing toggle handler owns open state and its child click opens narrow navigation', () => {
  const ui = browser();
  ui.click(ui.icon);
  assert.equal(ui.toggleCalls(), 1);
  assert.equal(ui.sidebar.classList.contains('open'), true);
  assert.equal(ui.toggle.getAttribute('aria-expanded'), 'true');
  assert.equal(ui.document.body.classList.contains('vq-drawer-open'), true);
  assert.equal(ui.document.activeElement, ui.nav);
  ui.click(ui.toggle);
  assert.equal(ui.toggleCalls(), 2);
  assert.equal(ui.sidebar.classList.contains('open'), false);
  assert.equal(ui.toggle.getAttribute('aria-expanded'), 'false');
  assert.equal(ui.document.body.classList.contains('vq-drawer-open'), false);
});

test('Escape closes a narrow open drawer, clears aria state, and returns focus to its toggle', () => {
  const ui = browser({ sidebarId: 'main-sidebar' });
  assert.equal(ui.key('Escape').defaultPrevented, false);
  ui.click(ui.toggle);
  assert.equal(ui.key('Enter').defaultPrevented, false);
  assert.equal(ui.sidebar.classList.contains('open'), true);
  assert.equal(ui.key('Escape').defaultPrevented, true);
  assert.equal(ui.sidebar.classList.contains('open'), false);
  assert.equal(ui.toggle.getAttribute('aria-expanded'), 'false');
  assert.equal(ui.document.body.classList.contains('vq-drawer-open'), false);
  assert.equal(ui.document.activeElement, ui.toggle);
  assert.equal(ui.toggle.focusCount, 1);
});

test('Backdrop dismissal returns focus without invoking the existing toggle handler again', () => {
  const ui = browser(); ui.click(ui.toggle); ui.click(ui.backdrop());
  assert.equal(ui.sidebar.classList.contains('open'), false);
  assert.equal(ui.toggle.getAttribute('aria-expanded'), 'false');
  assert.equal(ui.document.body.classList.contains('vq-drawer-open'), false);
  assert.equal(ui.document.activeElement, ui.toggle);
  assert.equal(ui.toggleCalls(), 1);
});

test('Resize keeps a drawer at 991px and clears it at the 992px desktop breakpoint', () => {
  const ui = browser(); ui.click(ui.toggle); ui.resize(991);
  assert.equal(ui.sidebar.classList.contains('open'), true);
  ui.resize(992);
  assert.equal(ui.sidebar.classList.contains('open'), false);
  assert.equal(ui.toggle.getAttribute('aria-expanded'), 'false');
  assert.equal(ui.document.body.classList.contains('vq-drawer-open'), false);
  assert.equal(ui.toggle.focusCount, 0, 'Responsive cleanup does not steal focus');
  ui.click(ui.toggle);
  assert.equal(ui.document.body.classList.contains('vq-drawer-open'), false);
  assert.equal(ui.key('Escape').defaultPrevented, false, 'Desktop navigation is not a drawer');
});

test('Focus stays usable inside navigation and on its toggle, then closes on leaving', () => {
  const ui = browser(); ui.click(ui.toggle);
  ui.secondNav.focus();
  assert.equal(ui.sidebar.classList.contains('open'), true);
  ui.toggle.focus();
  assert.equal(ui.sidebar.classList.contains('open'), true);
  ui.outside.focus();
  assert.equal(ui.sidebar.classList.contains('open'), false);
  assert.equal(ui.toggle.getAttribute('aria-expanded'), 'false');
  assert.equal(ui.document.body.classList.contains('vq-drawer-open'), false);
  assert.equal(ui.document.activeElement, ui.outside, 'Tab or external focus continues to its intended control');
});

test('Navigation keeps its URL, data attributes, existing handler, and default link behavior', () => {
  const ui = browser(); ui.click(ui.toggle);
  const event = ui.click(ui.nav);
  assert.equal(ui.navigationCalls(), 1);
  assert.equal(event.defaultPrevented, false);
  assert.equal(ui.nav.getAttribute('href'), 'admin/users.html');
  assert.equal(ui.nav.getAttribute('data-admin-nav'), 'users');
  assert.equal(ui.document.body.classList.contains('vq-drawer-open'), false);
  assert.equal(ui.toggleCalls(), 1);
});

test('Independent navigation and locale events dismiss drawers and clear stale scroll lock without moving focus', () => {
  for (const [eventName, eventTarget] of [['hashchange', 'window'], ['pagehide', 'window'], ['veriqra:localechange', 'document']]) {
    for (const alreadyClosedByNavigation of [false, true]) {
      const ui = browser(); ui.click(ui.toggle);
      // Existing application navigation can remove the visual drawer before the event reaches the shell.
      if (alreadyClosedByNavigation) ui.sidebar.classList.remove('open');
      assert.equal(ui.document.body.classList.contains('vq-drawer-open'), true);
      assert.equal(ui.toggle.getAttribute('aria-expanded'), 'true');
      const focusedBeforeNavigation = ui.document.activeElement;
      ui[eventTarget].dispatch(eventName, {});
      assert.equal(ui.sidebar.classList.contains('open'), false, eventName);
      assert.equal(ui.toggle.getAttribute('aria-expanded'), 'false', eventName);
      assert.equal(ui.document.body.classList.contains('vq-drawer-open'), false, eventName);
      assert.equal(ui.document.activeElement, focusedBeforeNavigation, eventName + ' must not steal focus');
      assert.equal(ui.toggle.focusCount, 0);
      assert.equal(ui.nav.focusCount, 1);
      assert.deepEqual(ui.businessCalls, []);
    }
  }
});

test('Drawer handling performs no authentication, fetch, or API requests', () => {
  const ui = browser();
  ui.click(ui.toggle); ui.key('Escape');
  ui.click(ui.toggle); ui.click(ui.backdrop());
  ui.click(ui.toggle); ui.outside.focus();
  ui.click(ui.toggle); ui.resize(1280);
  assert.deepEqual(ui.businessCalls, []);
});
