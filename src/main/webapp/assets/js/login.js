(function ($, api) {
  'use strict';
  const t = window.I18n.t;
  const form = $('#login-form');
  const submit = $('#login-submit');
  const error = $('#login-error');
  let busy = false;

  function feedback(message) { error.text(message).toggleClass('d-none', !message); }
  function setBusy(value) {
    busy = value;
    submit.prop('disabled', value);
    submit.find('.button-label').text(value ? t("auth.signingIn", null, 'Signing in…') : t("common.signIn", null, 'Sign in'));
    submit.find('.spinner-border').toggleClass('d-none', !value);
  }
  form.on('submit', function (event) {
    event.preventDefault();
    if (busy) return;
    feedback('');
    const username = $('#username').val().trim();
    const password = $('#password').val();
    if (!username || !password) { feedback(t("auth.enterYourUsernameAndPassword", null, 'Enter your username and password.')); return; }
    setBusy(true);
    api.post('auth/login', { username: username, password: password }, { authRequired: false })
      .done(function () { window.location.replace(new URL('index.html', document.baseURI).href); })
      .fail(function (failure) {
        feedback(failure.status === 401 ? t("auth.invalidUsernameOrPassword", null, 'Invalid username or password.') : failure.message);
      })
      .always(function () { setBusy(false); });
  });
  // A server session, never a browser flag, decides whether this page is needed.
  window.I18n.init().then(function () {
    api.get('auth/me', { authRequired: false }).done(function () {
      window.location.replace(new URL('index.html', document.baseURI).href);
    }).fail(function (failure) {
      // A missing session is the normal login-page state, not a retryable error.
      if (failure.status !== 401) feedback(failure.message);
    });
  });
  document.addEventListener('veriqra:localechange', function () { if (!busy) setBusy(false); });
})(jQuery, window.VeriqraApi);
