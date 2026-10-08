/* Shared drawer dismissal only. Existing navigation/auth/project handlers own state. */
(function () {
  'use strict';
  function init() {
    const sidebars = Array.from(document.querySelectorAll('.sidebar'));
    const backdrops = new Map();
    function close(sidebar) {
      const active = document.activeElement;
      const restoreFocus = window.innerWidth < 992 && (sidebar.contains(active) || active === backdrops.get(sidebar));
      sidebar.classList.remove('open');
      const toggle = document.querySelector('[aria-controls="' + sidebar.id + '"]');
      if (toggle) { toggle.setAttribute('aria-expanded', 'false'); if (restoreFocus) toggle.focus(); }
      document.body.classList.remove('vq-drawer-open');
    }
    sidebars.forEach(function (sidebar) {
      const backdrop = document.createElement('button');
      backdrop.type = 'button'; backdrop.className = 'vq-drawer-backdrop'; backdrop.tabIndex = -1;
      backdrop.setAttribute('data-i18n-aria-label', 'common.close');
      backdrop.setAttribute('aria-label', window.I18n.t('common.close'));
      backdrops.set(sidebar, backdrop);
      sidebar.after(backdrop);
      backdrop.addEventListener('click', function () { close(sidebar); });
    });
    document.addEventListener('click', function (event) {
      const toggle = event.target.closest('[aria-controls]');
      if (!toggle || !sidebars.some(sidebar => sidebar.id === toggle.getAttribute('aria-controls'))) return;
      const controlled = sidebars.find(sidebar => sidebar.id === toggle.getAttribute('aria-controls'));
      if (!controlled.classList.contains('open')) close(controlled);
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
      if (event.key === 'Escape') { event.preventDefault(); close(sidebar); }
      // The drawer is a navigation region, not a modal. Tab can leave it and closes it.
    });
    document.addEventListener('focusin', function (event) {
      const sidebar = sidebars.find(sidebar => sidebar.classList.contains('open'));
      if (sidebar && !sidebar.contains(event.target) && event.target.getAttribute('aria-controls') !== sidebar.id) close(sidebar);
    });
    document.addEventListener('click', function (event) {
      const link = event.target.closest('.sidebar a');
      if (link) { const sidebar = sidebars.find(sidebar => sidebar.contains(link)); if (sidebar) close(sidebar); }
    });
    window.addEventListener('resize', function () { if (window.innerWidth >= 992) sidebars.forEach(sidebar => close(sidebar)); });
    function dismiss() { sidebars.forEach(sidebar => close(sidebar)); }
    window.addEventListener('hashchange', dismiss);
    window.addEventListener('pagehide', dismiss);
    document.addEventListener('veriqra:localechange', dismiss);
    document.addEventListener('veriqra:view', dismiss);
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init); else init();
})();
