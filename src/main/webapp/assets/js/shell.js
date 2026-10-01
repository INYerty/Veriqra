/* Shared drawer dismissal only. Existing navigation/auth/project handlers own state. */
(function () {
  'use strict';
  function init() {
    const sidebars = Array.from(document.querySelectorAll('.sidebar'));
    function close(sidebar, returnFocus) {
      sidebar.classList.remove('open');
      const toggle = document.querySelector('[aria-controls="' + sidebar.id + '"]');
      if (toggle) { toggle.setAttribute('aria-expanded', 'false'); if (returnFocus) toggle.focus(); }
      document.body.classList.remove('vq-drawer-open');
    }
    sidebars.forEach(function (sidebar) {
      const backdrop = document.createElement('button');
      backdrop.type = 'button'; backdrop.className = 'vq-drawer-backdrop'; backdrop.tabIndex = -1;
      backdrop.setAttribute('data-i18n-aria-label', 'common.close');
      backdrop.setAttribute('aria-label', window.I18n.t('common.close'));
      sidebar.after(backdrop);
      backdrop.addEventListener('click', function () { close(sidebar, true); });
    });
    document.addEventListener('click', function (event) {
      const toggle = event.target.closest('[aria-controls]');
      if (!toggle || !sidebars.some(sidebar => sidebar.id === toggle.getAttribute('aria-controls'))) return;
      const opened = sidebars.some(sidebar => sidebar.classList.contains('open'));
      document.body.classList.toggle('vq-drawer-open', opened && window.innerWidth < 992);
      if (opened && window.innerWidth < 992) {
        const sidebar = sidebars.find(sidebar => sidebar.classList.contains('open'));
        sidebar.querySelector('a, button')?.focus();
      }
    });
    document.addEventListener('keydown', function (event) {
      const sidebar = sidebars.find(sidebar => sidebar.classList.contains('open'));
      if (!sidebar || window.innerWidth >= 992) return;
      if (event.key === 'Escape') { event.preventDefault(); close(sidebar, true); }
      // The drawer is a navigation region, not a modal. Tab can leave it and closes it.
    });
    document.addEventListener('focusin', function (event) {
      const sidebar = sidebars.find(sidebar => sidebar.classList.contains('open'));
      if (sidebar && !sidebar.contains(event.target) && event.target.getAttribute('aria-controls') !== sidebar.id) close(sidebar, false);
    });
    document.addEventListener('click', function (event) {
      if (event.target.closest('.sidebar a')) document.body.classList.remove('vq-drawer-open');
    });
    window.addEventListener('resize', function () { if (window.innerWidth >= 992) sidebars.forEach(sidebar => close(sidebar, false)); });
    function dismiss() { sidebars.forEach(sidebar => close(sidebar, false)); }
    window.addEventListener('hashchange', dismiss);
    window.addEventListener('pagehide', dismiss);
    document.addEventListener('veriqra:localechange', dismiss);
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init); else init();
})();
