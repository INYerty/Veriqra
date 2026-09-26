(function (global, $) {
  'use strict';
  const t = window.I18n.t;
  const messages = {
    400: ['http.pleaseCheckTheInformationYouEntered', 'Please check the information you entered.'],
    401: ['http.yourSessionHasExpiredPleaseSignInAgain', 'Your session has expired. Please sign in again.'],
    403: ['http.youDoNotHaveAccessToThisAction', 'You do not have access to this action.'],
    404: ['http.theRequestedItemIsNoLongerAvailable', 'The requested item is no longer available.'],
    409: ['http.thisItemHasChangedRefreshAndTryAgain', 'This item has changed. Refresh and try again.'],
    413: ['http.theXMLFileExceedsThe5MiBUploadLimit', 'The XML file exceeds the 5 MiB upload limit.'],
    415: ['http.theServerRequiresApplicationXmlForThisUpload', 'The server requires application/xml for this upload.'],
    429: ['http.tooManyRequestsPleaseTryAgainShortly', 'Too many requests. Please try again shortly.']
  };

  function onLoginPage() { return global.location.pathname.endsWith('/login.html'); }
  function signIn() { global.location.replace(new URL('login.html', document.baseURI).href); }
  function errorFrom(xhr, loginAttempt) {
    const status = xhr.status || 0;
    const retryHeader = xhr.getResponseHeader('Retry-After');
    const seconds = retryHeader == null ? NaN : Number(retryHeader);
    const entry = messages[status] || (status >= 500
      ? ['http.theServerIsUnavailablePleaseTryAgainLater', 'The server is unavailable. Please try again later.']
      : ['http.theRequestCouldNotBeCompleted', 'The request could not be completed.']);
    let message = t(entry[0], null, entry[1]);
    if (status === 429 && loginAttempt) {
      const delay = global.I18n.formatRetryDelay(seconds);
      message = delay ? t('auth.loginRateLimited', { time: delay }, 'Too many login attempts. Please try again in {time}.')
        : t('auth.loginRateLimitedGeneric', null, 'Too many login attempts. Please try again later.');
    } else if (status === 429 && Number.isFinite(seconds) && seconds > 0) {
      message = t('http.retryAfterSeconds', { seconds: Math.ceil(seconds) }, 'Too many requests. Try again in {seconds} seconds.');
    }
    const code = xhr.responseJSON && xhr.responseJSON.error && xhr.responseJSON.error.code;
    return { status: status, message: global.I18n.error({ code: code, message: message }), code: code };
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
      const error = errorFrom(xhr, method === 'POST' && path === 'auth/login');
      if (error.status === 401 && settings.authRequired !== false && !onLoginPage()) signIn();
      result.reject(error);
    });
    return result.promise();
  }
  function xml(path, file) {
    const url = new URL('api/' + path.replace(/^\/+/, ''), document.baseURI);
    const result = $.Deferred();
    $.ajax({ url: url.href, method: 'POST', data: file, processData: false,
      contentType: 'application/xml', dataType: 'json', headers: { 'X-Veriqra-Request': '1' } })
      .done(function (data) { result.resolve(data); })
      .fail(function (xhr) {
        const error = errorFrom(xhr, false);
        if (error.status === 401 && !onLoginPage()) signIn();
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
    xml: xml,
    signIn: signIn
  });
})(window, jQuery);
