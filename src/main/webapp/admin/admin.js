(function ($, api) {
  'use strict';
  const pageName = $('body').data('admin-page');
  const content = $('#admin-content');
  const feedbackBox = $('#admin-feedback');
  const state = { page: 1, pageSize: 25, filters: {}, generation: 0, creditSelection: new Map() };
  const creditHistoryPage = { page: 1 };
  if (pageName === 'credits') {
    const username = new URLSearchParams(location.search).get('username');
    if (username) state.filters.username = username;
  }
  const pages = ['index', 'users', 'credits', 'login-history', 'access-logs', 'audit-log', 'sessions', 'security', 'system'];
  const fmt = value => {
    if (value == null || value === '') return '—';
    const text = String(value);
    return /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(text) ? text.replace('T', ' ') : text;
  };
  const number = value => { try { return BigInt(value == null ? 0 : value).toLocaleString(); }
    catch (_) { return String(value); } };
  const action = (label, callback, style) => $('<button type="button" class="btn btn-sm">')
    .addClass(style || 'btn-outline-primary').text(label).on('click', callback);
  const cell = value => $('<td>').text(fmt(value));
  function feedback(message, error) {
    feedbackBox.text(message || '').toggleClass('d-none', !message)
      .toggleClass('alert-danger', !!error).toggleClass('alert-success', !!message && !error)
      .attr('role', error ? 'alert' : 'status');
  }
  function errorMessage(error) {
    const codes = { LAST_ADMIN_REQUIRED: 'The last active administrator cannot be disabled or demoted.',
      INSUFFICIENT_CREDIT_BALANCE: 'The account does not have enough Credits.',
      BATCH_RECIPIENTS_CHANGED: 'The active-user list changed. Review and confirm the batch again.',
      USERNAME_CONFLICT: 'That username is already in use.' };
    return codes[error.code] || error.message || 'The request could not be completed.';
  }
  async function load(path) {
    const token = state.generation;
    const result = await api.get('admin/' + path);
    if (token !== state.generation) throw { message: 'Superseded request' };
    return result;
  }
  async function post(path, body) { return api.post('admin/' + path, body); }
  function panel(title) { return $('<section class="asset-panel">').append($('<h2 class="fs-5">').text(title)); }
  function cards(values) {
    const grid = $('<div class="admin-grid mb-4">');
    values.forEach(([name, value]) => grid.append($('<div class="admin-card">')
      .append($('<small>').text(name), $('<strong>').text(number(value)))));
    return grid;
  }
  function table(headers, rows, cells) {
    if (!rows.length) return $('<p class="empty-state">').text('No matching records.');
    const table = $('<table class="admin-table">');
    const head = $('<tr>'); headers.forEach(h => head.append($('<th scope="col">').text(h)));
    table.append($('<thead>').append(head));
    const body = $('<tbody>');
    rows.forEach(row => {
      const tr = $('<tr>');
      cells(row).forEach(value => tr.append(value && value.jquery ? $('<td>').append(value) : cell(value)));
      body.append(tr);
    });
    return $('<div class="admin-table-wrap">').append(table.append(body));
  }
  function paging(result, rerender, pageState = state) {
    const totalPages = Math.max(1, Math.ceil(result.total / result.pageSize));
    return $('<div class="admin-pagination">').append(
      $('<span>').text('Page ' + result.page + ' of ' + totalPages + ' · ' + result.total + ' record(s)'),
      $('<div class="d-flex gap-2">').append(
        action('Previous', () => { pageState.page--; rerender(); }).prop('disabled', pageState.page <= 1),
        action('Next', () => { pageState.page++; rerender(); }).prop('disabled', pageState.page >= totalPages)));
  }
  function query(extra) {
    const params = new URLSearchParams({ page: String(state.page), pageSize: String(state.pageSize) });
    Object.entries({ ...state.filters, ...(extra || {}) }).forEach(([k, v]) => { if (v != null && v !== '') params.set(k, v); });
    return '?' + params.toString();
  }
  function filters(fields, rerender, pageState = state) {
    const form = $('<form class="admin-toolbar">');
    fields.forEach(([name, label, type, choices]) => {
      const wrap = $('<label>').text(label);
      const input = choices ? $('<select class="form-select form-select-sm">') : $('<input class="form-control form-control-sm">').attr('type', type || 'text');
      input.attr('name', name).val(state.filters[name] || '');
      if (choices) { input.append($('<option>').val('').text('All')); choices.forEach(choice => input.append($('<option>').val(choice).text(choice))); input.val(state.filters[name] || ''); }
      wrap.append(input); form.append(wrap);
    });
    form.append(action('Apply filters', () => form.trigger('submit'), 'btn-primary'),
      action('Clear', () => { state.filters = {}; pageState.page = 1; rerender(); }, 'btn-outline-secondary'));
    form.on('submit', event => {
      event.preventDefault(); state.filters = {};
      form.find('input,select').each(function () { if (this.value) state.filters[this.name] = this.value; });
      pageState.page = 1; rerender();
    });
    return form;
  }
  function modal(title, fields, submitLabel, submit) {
    const root = $('<div class="modal fade" tabindex="-1" aria-hidden="true">');
    const box = $('<div class="modal-dialog modal-dialog-scrollable">');
    const inner = $('<div class="modal-content">');
    const header = $('<div class="modal-header">').append($('<h2 class="modal-title fs-5">').text(title),
      $('<button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="Close">'));
    const form = $('<form novalidate>');
    const body = $('<div class="modal-body">');
    fields.forEach(field => {
      const wrap = $('<div class="mb-3">');
      const id = 'admin-field-' + field.name;
      wrap.append($('<label class="form-label">').attr('for', id).text(field.label));
      let input;
      if (field.options) {
        input = $('<select class="form-select">');
        field.options.forEach(option => input.append($('<option>').val(option).text(option)));
      } else if (field.type === 'textarea') input = $('<textarea class="form-control" rows="3">');
      else input = $('<input class="form-control">').attr('type', field.type || 'text');
      const initialValue = field.value == null ? (field.options?.[0] || '') : field.value;
      input.attr({ id: id, name: field.name }).val(initialValue);
      if (field.required) input.prop('required', true);
      if (field.readonly) input.prop('readOnly', true);
      if (field.min != null) input.attr('min', field.min);
      wrap.append(input); body.append(wrap);
    });
    const error = $('<div class="alert alert-danger d-none" role="alert" aria-live="assertive">');
    body.append(error);
    const send = $('<button type="submit" class="btn btn-primary">').text(submitLabel);
    const footer = $('<div class="modal-footer">').append($('<button type="button" class="btn btn-outline-secondary" data-bs-dismiss="modal">').text('Cancel'), send);
    form.append(body, footer); inner.append(header, form); root.append(box.append(inner)); $('body').append(root);
    const instance = new bootstrap.Modal(root[0]);
    let shown = false;
    root.on('shown.bs.modal', () => { shown = true; });
    root.on('hidden.bs.modal', () => { instance.dispose(); root.remove(); });
    form.on('submit', async event => {
      event.preventDefault(); if (!form[0].reportValidity() || send.prop('disabled')) return;
      const values = {}; form.find('[name]').each(function () { values[this.name] = this.value; });
      send.prop('disabled', true); error.addClass('d-none').text('');
      try { const result = await submit(values); if (result === false) return;
        if (shown) instance.hide(); else root.one('shown.bs.modal', () => instance.hide());
        if (result !== null) { await render(); feedback('Change saved.'); } }
      catch (failure) { error.removeClass('d-none').text(errorMessage(failure)); }
      finally { send.prop('disabled', false); }
    });
    instance.show();
  }
  async function renderDashboard() {
    const result = await load('dashboard');
    const m = result.metrics, c = result.credits;
    content.empty().append(cards([
      ['Users', m.users], ['Active users', m.activeUsers], ['Disabled users', m.disabledUsers], ['Administrators', m.admins],
      ['Logins today', m.loginsToday], ['Failed logins today', m.failedLoginsToday], ['Rate limited today', m.rateLimitedToday],
      ['Requests today', m.requestsToday], ['4xx today', m.clientErrorsToday], ['5xx today', m.serverErrorsToday],
      ['Unique IPs today', m.uniqueIpsToday],
      ['Total Credit balance', c.totalBalance], ['Credits issued today', m.creditsIssuedToday],
      ['Credits reclaimed today', m.creditsReclaimedToday]]));
    content.append(panel('Recent login failures').append(table(['Time', 'Username', 'IP', 'Result'], result.recentFailures,
      row => [fmt(row.createdAt), row.usernameAttempted, row.ipAddress, row.result])));
    content.append(panel('Recent admin actions').append(table(['Time', 'Action', 'Actor ID', 'Summary'], result.recentActions,
      row => [fmt(row.createdAt), row.action, row.actorUserId, row.summary])));
    content.append(panel('System').append($('<p class="mb-0">').text('Veriqra ' + result.system.version
      + ' · Application ' + result.system.applicationStatus + ' · Database ' + result.system.databaseStatus)));
  }
  async function renderUsers() {
    const result = await load('users' + query());
    const top = $('<div class="d-flex flex-wrap justify-content-between align-items-center gap-2 mb-3">')
      .append($('<p class="mb-0">').text('Manage accounts without deleting historical users.'),
        action('Create user', () => modal('Create user', [
          { name: 'username', label: 'Username', required: true },
          { name: 'displayName', label: 'Display name', required: true },
          { name: 'password', label: 'Temporary password (12+ characters)', type: 'password', required: true },
          { name: 'systemRole', label: 'System role', options: ['USER', 'ADMIN'] },
          { name: 'status', label: 'Status', options: ['ACTIVE', 'DISABLED'] }
        ], 'Create user', values => post('users', values)), 'btn-primary'));
    content.empty().append(top, table(['Username', 'Display name', 'Role', 'Status', 'Last login', 'Last seen', 'IP', 'Device', 'Credits', 'Actions'], result.items, user => [
      user.username, user.displayName, user.systemRole, user.status, fmt(user.lastLogin), fmt(user.lastSeen), user.lastIp,
      user.lastDevice, number(user.creditBalance), $('<div class="actions">').append(
        action('Edit', () => modal('Edit ' + user.username, [
          { name: 'displayName', label: 'Display name', value: user.displayName, required: true },
          { name: 'systemRole', label: 'System role', options: ['USER', 'ADMIN'], value: user.systemRole },
          { name: 'status', label: 'Status', options: ['ACTIVE', 'DISABLED'], value: user.status }
        ], 'Save', values => api.action('PATCH', 'admin/users/' + user.id, { ...values, lockVersion: user.lockVersion }))),
        action('Reset password', () => modal('Reset password for ' + user.username,
          [{ name: 'password', label: 'New password (12+ characters)', type: 'password', required: true }],
          'Reset password', values => post('users/' + user.id + '/reset-password', values)), 'btn-outline-danger'),
        action('Credits', () => viewUserCredits(user)),
        action('Grant', () => creditChange({ userId: user.id, username: user.username }, 'grant')),
        action('Reclaim', () => creditChange({ userId: user.id, username: user.username }, 'reclaim'), 'btn-outline-danger'))
    ]), paging(result, render));
  }
  async function viewUserCredits(user) {
    try {
      const result = await load('users/' + user.id + '/credits');
      const history = result.transactions.map(row => [fmt(row.createdAt), row.type, row.amount,
        'actor #' + row.actorUserId, row.reason || '—'].join(' · ')).join('\n');
      modal('Experimental Credits · ' + user.username, [
        { name: 'balance', label: 'Current balance', value: result.account.balance, readonly: true },
        { name: 'history', label: 'Recent 10 ledger entries', type: 'textarea', value: history || 'No Credit changes yet.', readonly: true }
      ], 'Close', async () => null);
    } catch (error) { feedback(errorMessage(error), true); }
  }
  async function renderCredits() {
    const [result, summary] = await Promise.all([load('credits' + query()), load('credits/summary')]);
    const selected = state.creditSelection;
    const rowActions = account => $('<div class="actions">').append(
      action('Grant', () => creditChange(account, 'grant')),
      action('Reclaim', () => creditChange(account, 'reclaim'), 'btn-outline-danger'),
      action('History', () => { state.filters = { userId: String(account.userId) }; creditHistoryPage.page = 1; renderCreditHistory(); }));
    const batch = action('Batch Grant', () => {
      modal('Batch Experimental Credits', [
        { name: 'scope', label: 'Recipients', options: ['SELECTED_USERS', 'ALL_ACTIVE_USERS'] },
        { name: 'amount', label: 'Amount per user', type: 'number', min: 1, required: true },
        { name: 'reason', label: 'Reason (recommended)' }
      ], 'Review and grant', async values => {
        const all = values.scope === 'ALL_ACTIVE_USERS';
        const ids = [...selected.keys()];
        const preview = all ? await load('credits/recipients') : null;
        const recipients = all ? preview.length : ids.length;
        const amount = Number(values.amount);
        if (!Number.isSafeInteger(amount) || amount <= 0 || recipients <= 0 || recipients > 500)
          throw { message: 'Select 1–500 recipients and a positive whole amount.' };
        const total = BigInt(values.amount) * BigInt(recipients);
        if (!window.confirm('Experimental Credits\nUsers: ' + recipients + '\nAmount per user: ' + amount
          + '\nTotal issuance: ' + total + '\nConfirm this batch?')) return false;
        await post('credits/batch-grant', { scope: values.scope, userIds: all ? null : ids,
          expectedActiveUserIds: all ? preview : null,
          amount: amount, reason: values.reason || null });
        selected.clear();
      });
    }, 'btn-primary');
    const rows = result.items;
    content.empty().append($('<p class="text-secondary">').text('Experimental internal Credits have no monetary value or consumption feature.'),
      cards([['Total balance', summary.totalBalance], ['Total issued', summary.totalIssued],
        ['Total reclaimed', summary.totalReclaimed], ['Users with balance', summary.usersWithBalance]]), batch,
      $('<span class="ms-2" id="credit-selected-count" role="status">').text(selected.size + ' selected'),
      action('Clear selection', () => { selected.clear(); render(); }, 'btn-outline-secondary'),
      $('<div class="mt-3">').append(table(['Select', 'Username', 'Display name', 'Status', 'Balance', 'Last change', 'Actions'], rows,
        account => [
          $('<input type="checkbox" class="form-check-input" aria-label="Select recipient">')
            .prop('checked', selected.has(account.userId)).on('change', function () {
              if (this.checked) selected.set(account.userId, account.username); else selected.delete(account.userId);
              $('#credit-selected-count').text(selected.size + ' selected');
            }), account.username, account.displayName, account.status, number(account.balance), fmt(account.updatedAt), rowActions(account)
        ])), paging(result, render), panel('Credit ledger').append($('<div id="credit-history">')));
    await renderCreditHistory();
  }
  function creditChange(account, kind) {
    modal((kind === 'grant' ? 'Grant to ' : 'Reclaim from ') + account.username,
      [{ name: 'amount', label: 'Positive Credit amount', type: 'number', min: 1, required: true },
        { name: 'reason', label: 'Reason (recommended)' }], kind === 'grant' ? 'Grant' : 'Reclaim', async values => {
        const amount = Number(values.amount);
        if (!Number.isSafeInteger(amount) || amount <= 0) throw { message: 'Enter a positive whole amount.' };
        if (kind === 'reclaim' && !window.confirm('Reclaim ' + amount + ' Credits from ' + account.username + '?')) return false;
        await post('users/' + account.userId + '/credits/' + kind, { amount: amount, reason: values.reason || null });
      });
  }
  async function renderCreditHistory() {
    const target = $('#credit-history'); if (!target.length) return;
    const filter = filters([['username', 'Username'], ['type', 'Type', null, ['GRANT', 'RECLAIM']],
      ['actorId', 'Actor ID'], ['batchId', 'Batch ID'], ['from', 'From', 'datetime-local'], ['to', 'To', 'datetime-local']], renderCreditHistory, creditHistoryPage);
    const result = await load('credits/transactions' + query({ page: String(creditHistoryPage.page) }));
    target.empty().append(filter, table(['Time', 'User ID', 'Type', 'Amount', 'Actor ID', 'Batch ID', 'Reason'], result.items,
      row => [fmt(row.createdAt), row.userId, row.type, number(row.amount), row.actorUserId, row.batchId, row.reason]),
      paging(result, renderCreditHistory, creditHistoryPage));
  }
  async function renderLog(path, fields, headers, values) {
    const result = await load(path + query());
    content.empty().append(filters(fields, render), table(headers, result.items, values), paging(result, render));
  }
  async function renderSessions() {
    const result = await load('sessions' + query());
    content.empty().append($('<div class="alert alert-info">').text('Login activity only. Last seen is user-level request activity, not a live-session timestamp. No online-session registry or force logout is available.'),
      table(['Login time', 'User last seen', 'User ID', 'Username', 'IP', 'Device'], result.activity.items,
        row => [fmt(row.loginTime), fmt(row.userLastSeen), row.userId, row.username, row.ipAddress, row.deviceType]),
      paging(result.activity, render));
  }
  async function renderSecurity() {
    const result = await load('security'); const m = result.metrics;
    content.empty().append(cards([['Failed logins today', m.failedLoginsToday], ['Rate limited today', m.rateLimitedToday],
      ['4xx today', m.clientErrorsToday], ['5xx today', m.serverErrorsToday], ['Unique IPs today', m.uniqueIpsToday]]),
      panel('Recent failures').append(table(['Time', 'Username', 'IP', 'Device'], result.recentFailures,
        row => [fmt(row.createdAt), row.usernameAttempted, row.ipAddress, row.deviceType])),
      panel('Recent rate limits').append(table(['Time', 'Username', 'IP'], result.recentRateLimits,
        row => [fmt(row.createdAt), row.usernameAttempted, row.ipAddress])));
  }
  async function renderSystem() {
    const result = await load('system'); const dl = $('<dl class="admin-detail">');
    Object.entries(result).forEach(([key, value]) => dl.append($('<div>').append($('<dt>').text(key), $('<dd>').text(fmt(value)))));
    content.empty().append(panel('Read-only system information').append(dl));
  }
  async function render() {
    const token = ++state.generation;
    content.empty().append($('<p role="status">').text('Loading…')); feedback('');
    try {
      switch (pageName) {
        case 'index': await renderDashboard(); break;
        case 'users': await renderUsers(); break;
        case 'credits': await renderCredits(); break;
        case 'login-history': await renderLog('login-history',
          [['from', 'From', 'datetime-local'], ['to', 'To', 'datetime-local'], ['username', 'Username'],
            ['result', 'Result', null, ['SUCCESS', 'FAILURE', 'RATE_LIMITED']], ['ip', 'IP'],
            ['deviceType', 'Device', null, ['Desktop', 'Mobile', 'Tablet', 'Other']]],
          ['Time', 'Username', 'Result', 'IP', 'Browser', 'OS', 'Device'],
          row => [fmt(row.createdAt), row.usernameAttempted, row.result, row.ipAddress, row.browser, row.operatingSystem, row.deviceType]); break;
        case 'access-logs': await renderLog('access-logs',
          [['from', 'From', 'datetime-local'], ['to', 'To', 'datetime-local'], ['username', 'Username'], ['ip', 'IP'],
            ['status', 'Status'], ['method', 'Method'], ['deviceType', 'Device', null, ['Desktop', 'Mobile', 'Tablet', 'Other']]],
          ['Time', 'User', 'IP', 'Device', 'Method', 'Path', 'Status', 'Request ID', 'Details'],
          row => [fmt(row.createdAt), row.username || (row.userId ? '#' + row.userId : 'Guest'), row.ipAddress, row.deviceType, row.httpMethod, row.requestPath,
            row.statusCode, row.requestId, action('View', () => modal('Access details', [
              { name: 'browser', label: 'Browser', value: row.browser, readonly: true }, { name: 'os', label: 'Operating system', value: row.operatingSystem, readonly: true },
              { name: 'device', label: 'Device', value: row.deviceType, readonly: true }, { name: 'agent', label: 'Full User-Agent', value: row.userAgent, readonly: true },
              { name: 'requestId', label: 'Request ID', value: row.requestId, readonly: true }
            ], 'Close', async () => null))]); break;
        case 'audit-log': await renderLog('audit-logs',
          [['from', 'From', 'datetime-local'], ['to', 'To', 'datetime-local'], ['actorId', 'Actor ID'], ['action', 'Action']],
          ['Time', 'Actor ID', 'Action', 'Target', 'Summary', 'Request ID'],
          row => [fmt(row.createdAt), row.actorUserId, row.action, row.targetType + ' #' + fmt(row.targetId), row.summary, row.requestId]); break;
        case 'sessions': await renderSessions(); break;
        case 'security': await renderSecurity(); break;
        case 'system': await renderSystem(); break;
      }
    } catch (error) { if (token === state.generation) { content.empty().append($('<p class="empty-state">').text('Unable to load this page.')); feedback(errorMessage(error), true); } }
  }
  $('#admin-menu').on('click', function () {
    const opened = $('#admin-sidebar').toggleClass('open').hasClass('open'); $(this).attr('aria-expanded', String(opened));
  });
  $('#admin-logout').on('click', async function () {
    try { await api.post('auth/logout'); location.replace(new URL('login.html', document.baseURI).href); }
    catch (error) { feedback(errorMessage(error), true); }
  });
  if (!pages.includes(pageName)) { content.text('Unknown administration page.'); return; }
  $('[data-admin-nav="' + pageName + '"]').addClass('active').attr('aria-current', 'page');
  api.get('auth/me').done(user => {
    $('#admin-user').text(user.username);
    if (user.systemRole !== 'ADMIN') { content.empty().append($('<p class="alert alert-danger">').text('Forbidden: platform administrator access is required.')); return; }
    render();
  }).fail(error => { if (error.status !== 401) feedback(errorMessage(error), true); });
})(jQuery, window.VeriqraApi);
