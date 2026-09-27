(function (global, document) {
  'use strict';
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
