(function (global, document) {
  'use strict';
  // The packaged URL carries the token generated from the existing build metadata.
  const scriptUrl = document.currentScript && document.currentScript.src;
  const script = scriptUrl ? new URL(scriptUrl, document.baseURI) : null;
  const candidate = script && script.searchParams.get('v');
  const version = /^(?:[0-9a-f]{40}|dev)-[0-9a-f]{64}$/i.test(candidate || '') ? candidate : null;
  const resourceRoot = script ? new URL('../../', script) : null;
  if (!version) console.warn('Unversioned Veriqra source preview; use a Maven WAR for release cache verification.');
  function assetUrl(value) {
    if (!version || !resourceRoot) return value;
    const url = new URL(value, document.baseURI);
    if (url.origin !== resourceRoot.origin || !url.pathname.startsWith(resourceRoot.pathname)) return value;
    const path = url.pathname.slice(resourceRoot.pathname.length);
    if (!/^(?:assets\/(?:js\/.*\.js|css\/.*\.css)|admin\/[^/]+\.(?:js|css)|i18n\/(?:en|zh-CN)\.json)$/.test(path)) return value;
    url.searchParams.set('v', version);
    return url.href;
  }
  global.VeriqraAssets = Object.freeze({ version: version, url: assetUrl });

  function preferredLocale() {
    let saved;
    try { saved = global.localStorage.getItem('veriqra.locale'); } catch (_) { /* Storage may be unavailable. */ }
    if (saved === 'en' || saved === 'zh-CN') return saved;
    const languages = global.navigator.languages && global.navigator.languages.length
      ? global.navigator.languages : [global.navigator.language];
    return /^zh(?:$|-(?:cn|sg|hans)(?:-|$))/.test(String(languages[0] || '').toLowerCase()) ? 'zh-CN' : 'en';
  }

  const locale = preferredLocale();
  document.documentElement.lang = locale;
  if (locale === 'zh-CN') document.documentElement.classList.add('i18n-pending');
  global.VeriqraLocaleBootstrap = Object.freeze({ locale: locale });

  document.addEventListener('DOMContentLoaded', function () {
    if (locale !== 'zh-CN') return;
    const reveal = function () { document.documentElement.classList.remove('i18n-pending'); };
    if (!global.I18n || typeof global.I18n.init !== 'function') { reveal(); return; }
    Promise.resolve().then(function () { return global.I18n.init(); })
      .catch(function (error) { console.warn(error); }).then(reveal);
  });
})(window, document);
