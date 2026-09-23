(function (global, $) {
  'use strict';
  const messages = {
    400: 'Please check the information you entered.',
    401: 'Your session has expired. Please sign in again.',
    403: 'You do not have access to this action.',
    404: 'The requested item is no longer available.',
    409: 'This item has changed. Refresh and try again.',
    429: 'Too many requests. Please try again shortly.'
  };

  function onLoginPage() { return global.location.pathname.endsWith('/login.html'); }
  function signIn() { global.location.replace(new URL('login.html', document.baseURI).href); }
  function errorFrom(xhr) {
    const status = xhr.status || 0;
    const seconds = Number(xhr.getResponseHeader('Retry-After'));
    let message = messages[status] || (status >= 500 ? 'The server is unavailable. Please try again later.' : 'The request could not be completed.');
    if (status === 429 && Number.isFinite(seconds) && seconds > 0) {
      message = 'Too many requests. Try again in ' + Math.ceil(seconds) + ' seconds.';
    }
    return { status: status, message: message, code: xhr.responseJSON && xhr.responseJSON.error && xhr.responseJSON.error.code };
  }
  function request(method, path, body, options) {
    const settings = options || {};
    const url = new URL('api/' + path.replace(/^\/+/, ''), document.baseURI);
    const isWrite = !['GET', 'HEAD'].includes(method);
    const call = $.ajax({
      url: url.href, method: method, dataType: 'json',
      contentType: isWrite ? 'application/json; charset=UTF-8' : undefined,
      data: body === undefined ? undefined : JSON.stringify(body),
      headers: isWrite ? { 'X-Veriqra-Request': '1' } : {},
      // The browser supplies the same-origin session cookie and Origin header.
    });
    const result = $.Deferred();
    call.done(function (data) { result.resolve(data); });
    call.fail(function (xhr) {
      const error = errorFrom(xhr);
      if (error.status === 401 && settings.authRequired !== false && !onLoginPage()) signIn();
      result.reject(error);
    });
    return result.promise();
  }
  global.VeriqraApi = Object.freeze({
    get: function (path, options) { return request('GET', path, undefined, options); },
    post: function (path, body, options) { return request('POST', path, body, options); },
    put: function (path, body, options) { return request('PUT', path, body, options); },
    delete: function (path, options) { return request('DELETE', path, undefined, options); },
    action: request,
    signIn: signIn
  });
})(window, jQuery);
