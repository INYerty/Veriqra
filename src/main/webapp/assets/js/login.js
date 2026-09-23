(function ($, api) {
  'use strict';
  const form = $('#login-form');
  const submit = $('#login-submit');
  const error = $('#login-error');
  let busy = false;

  function feedback(message) { error.text(message).toggleClass('d-none', !message); }
  function setBusy(value) {
    busy = value;
    submit.prop('disabled', value);
    submit.find('.button-label').text(value ? 'Signing in…' : 'Sign in');
    submit.find('.spinner-border').toggleClass('d-none', !value);
  }
  form.on('submit', function (event) {
    event.preventDefault();
    if (busy) return;
    feedback('');
    const username = $('#username').val().trim();
    const password = $('#password').val();
    if (!username || !password) { feedback('Enter your username and password.'); return; }
    setBusy(true);
    api.post('auth/login', { username: username, password: password }, { authRequired: false })
      .done(function () { window.location.replace(new URL('index.html', document.baseURI).href); })
      .fail(function (failure) {
        feedback(failure.status === 401 ? 'Invalid username or password.' : failure.message);
      })
      .always(function () { setBusy(false); });
  });
  // A server session, never a browser flag, decides whether this page is needed.
  api.get('auth/me', { authRequired: false }).done(function () {
    window.location.replace(new URL('index.html', document.baseURI).href);
  });
})(jQuery, window.VeriqraApi);
