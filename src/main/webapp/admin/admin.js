(function ($, api) {
  'use strict';
  const t = window.I18n.t;
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
  let authorized = false;
  let forbidden = false;
  const fmt = value => /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(String(value || ''))
    ? window.I18n.formatDateTime(value) : (value == null || value === '' ? '—' : String(value));
  const number = value => window.I18n.formatCredit(value);
  const display = value => window.I18n.enumLabel(value);
  const statusTone = value => ['ACTIVE', 'SUCCESS', 'UP'].includes(String(value)) ? 'success'
    : ['FAILURE', 'DOWN'].includes(String(value)) ? 'danger'
      : String(value) === 'RATE_LIMITED' ? 'warning' : 'neutral';
  const status = value => $('<span class="status-badge">').attr({ 'data-status': value, 'data-vq-tone': statusTone(value) }).text(display(value));
  const httpStatus = value => $('<span class="status-badge">').attr({ 'data-status': value,
    'data-vq-tone': Number(value) >= 500 ? 'danger' : Number(value) >= 400 ? 'warning' : Number(value) >= 200 && Number(value) < 300 ? 'success' : 'neutral' }).text(fmt(value));
  const operatingSystem = value => window.VeriqraAccessLogFormat.operatingSystem(value, t);
  const auditSummaryKeys = Object.freeze({
    'User created': 'admin.auditSummaryUserCreated',
    'User profile updated': 'admin.auditSummaryUserUpdated',
    'User status changed': 'admin.auditSummaryUserStatusChanged',
    'User role changed': 'admin.auditSummaryUserRoleChanged',
    'User password reset': 'admin.auditSummaryPasswordReset',
    'Experimental Credits grant': 'admin.auditSummaryCreditGrant',
    'Experimental Credits reclaim': 'admin.auditSummaryCreditReclaim',
    'Experimental Credits batch granted': 'admin.auditSummaryBatchGrant'
  });
  const auditSummary = value => auditSummaryKeys[value] ? t(auditSummaryKeys[value], null, value) : value;
  const action = (label, callback, style) => $('<button type="button" class="btn btn-sm">')
    .addClass(style || 'btn-outline-primary').text(label).on('click', callback);
  const cell = value => $('<td>').text(fmt(value));
  function feedback(message, error) {
    feedbackBox.text(message || '').toggleClass('d-none', !message)
      .toggleClass('alert-danger', !!error).toggleClass('alert-success', !!message && !error)
      .attr('role', error ? 'alert' : 'status');
  }
  function errorMessage(error) { return window.I18n.error(error); }
  function showForbidden() {
    content.empty().append($('<p class="alert alert-danger">').text(t('admin.forbiddenPlatformAdministratorAccessIsRequired', null,
      'Forbidden: platform administrator access is required.')));
  }
  async function load(path) {
    const token = state.generation;
    const result = await api.get('admin/' + path);
    if (token !== state.generation) throw { message: 'Superseded request' };
    return result;
  }
  async function post(path, body) { return api.post('admin/' + path, body); }
  function panel(title) { return $('<section class="asset-panel">').append($('<h2>').text(title)); }
  function cards(values) {
    const grid = $('<div class="admin-grid mb-4">');
    values.forEach(([name, value]) => grid.append($('<div class="admin-card">')
      .append($('<small>').text(name), $('<strong>').text(number(value)))));
    return grid;
  }
  function table(headers, rows, cells, columnTypes = []) {
    if (!rows.length) return $('<p class="empty-state">').text(t("admin.noMatchingRecords", null, 'No matching records.'));
    const table = $('<table class="admin-table">');
    const head = $('<tr>'); headers.forEach((h, index) => head.append($('<th scope="col">').attr('data-vq-type', columnTypes[index] || null).text(h)));
    table.append($('<thead>').append(head));
    const body = $('<tbody>');
    rows.forEach(row => {
      const tr = $('<tr>');
      cells(row).forEach((value, index) => tr.append((value && value.jquery ? $('<td>').append(value) : cell(value))
        .attr('data-vq-type', columnTypes[index] || null)));
      body.append(tr);
    });
    return $('<div class="admin-table-wrap">').append(table.append(body));
  }
  function paging(result, rerender, pageState = state) {
    const totalPages = Math.max(1, Math.ceil(result.total / result.pageSize));
    return $('<div class="admin-pagination">').append(
      $('<span>').text(t('admin.pageCount', { page: window.I18n.formatNumber(result.page),
        totalPages: window.I18n.formatNumber(totalPages), total: window.I18n.formatNumber(result.total) },
      'Page {page} of {totalPages} · {total} record(s)')),
      $('<div class="d-flex gap-2">').append(
        action(t("common.previous", null, 'Previous'), () => { pageState.page--; rerender(); }).prop('disabled', pageState.page <= 1),
        action(t("common.next", null, 'Next'), () => { pageState.page++; rerender(); }).prop('disabled', pageState.page >= totalPages)));
  }
  function query(extra) {
    const params = new URLSearchParams({ page: String(state.page), pageSize: String(state.pageSize) });
    Object.entries({ ...state.filters, ...(extra || {}) }).forEach(([k, v]) => {
      if (v != null && v !== '') params.set(k, k === 'from' || k === 'to' ? window.I18n.toUtcFilter(v) : v);
    });
    return '?' + params.toString();
  }
  function filters(fields, rerender, pageState = state) {
    const form = $('<form class="admin-toolbar">');
    fields.forEach(([name, label, type, choices]) => {
      const wrap = $('<label>').text(type === 'datetime-local'
        ? label + t('admin.shanghaiTimeSuffix', null, ' (Asia/Shanghai)') : label);
      const input = choices ? $('<select class="form-select form-select-sm">') : $('<input class="form-control form-control-sm">').attr('type', type || 'text');
      input.attr('name', name).val(state.filters[name] || '');
      if (choices) { input.append($('<option>').val('').text(t("admin.all", null, 'All'))); choices.forEach(choice => input.append($('<option>').val(choice).text(display(choice)))); input.val(state.filters[name] || ''); }
      wrap.append(input); form.append(wrap);
    });
    form.append(action(t("admin.applyFilters", null, 'Apply filters'), () => form.trigger('submit'), 'btn-primary'),
      action(t("common.clear", null, 'Clear'), () => { state.filters = {}; pageState.page = 1; rerender(); }, 'btn-outline-secondary'));
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
    const header = $('<div class="modal-header">').append($('<h2 class="modal-title">').text(title),
      $('<button type="button" class="btn-close" data-bs-dismiss="modal">').attr('aria-label', t('common.close')));
    const form = $('<form novalidate>');
    const body = $('<div class="modal-body">');
    fields.forEach(field => {
      const wrap = $('<div class="mb-3">');
      const id = 'admin-field-' + field.name;
      wrap.append($('<label class="form-label">').attr('for', id).text(field.label));
      let input;
      if (field.options) {
        input = $('<select class="form-select">');
        field.options.forEach(option => input.append($('<option>').val(option).text(display(option))));
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
    const footer = $('<div class="modal-footer">').append($('<button type="button" class="btn btn-outline-secondary" data-bs-dismiss="modal">').text(t("common.cancel", null, 'Cancel')), send);
    form.append(body, footer); inner.append(header, form); root.append(box.append(inner)); $('body').append(root);
    const instance = new bootstrap.Modal(root[0]);
    let shown = false;
    root.on('shown.bs.modal', () => { shown = true; });
    root.on('hidden.bs.modal', () => { instance.dispose(); root.remove(); });
    form.on('submit', async event => {
      event.preventDefault(); if (send.prop('disabled')) return;
      if (!form[0].checkValidity()) {
        const invalid = form.find(':invalid')[0];
        error.removeClass('d-none').text(invalid && invalid.type === 'number'
          ? t('admin.enterAPositiveWholeAmount', null, 'Enter a positive whole amount.')
          : t('admin.completeRequiredFields', null, 'Complete the required fields.'));
        if (invalid) invalid.focus();
        return;
      }
      const values = {}; form.find('[name]').each(function () { values[this.name] = this.value; });
      send.prop('disabled', true); error.addClass('d-none').text('');
      try { const result = await submit(values); if (result === false) return;
        if (shown) instance.hide(); else root.one('shown.bs.modal', () => instance.hide());
        if (result !== null) { await render(); feedback(t("admin.changeSaved", null, 'Change saved.')); } }
      catch (failure) { error.removeClass('d-none').text(errorMessage(failure)); }
      finally { send.prop('disabled', false); }
    });
    instance.show();
  }
  async function renderDashboard() {
    const result = await load('dashboard');
    const m = result.metrics, c = result.credits;
    content.empty().append(cards([
      [t("common.users", null, 'Users'), m.users], [t("admin.activeUsers", null, 'Active users'), m.activeUsers], [t("admin.disabledUsers", null, 'Disabled users'), m.disabledUsers], [t("admin.administrators", null, 'Administrators'), m.admins],
      [t("admin.loginsToday", null, 'Logins today'), m.loginsToday], [t("admin.failedLoginsToday", null, 'Failed logins today'), m.failedLoginsToday], [t("admin.rateLimitedToday", null, 'Rate limited today'), m.rateLimitedToday],
      [t("admin.requestsToday", null, 'Requests today'), m.requestsToday], [t("admin.4xxToday", null, '4xx today'), m.clientErrorsToday], [t("admin.5xxToday", null, '5xx today'), m.serverErrorsToday],
      [t("admin.uniqueIPsToday", null, 'Unique IPs today'), m.uniqueIpsToday],
      [t("admin.totalCreditBalance", null, 'Total Credit balance'), c.totalBalance], [t("admin.creditsIssuedToday", null, 'Credits issued today'), m.creditsIssuedToday],
      [t("admin.creditsReclaimedToday", null, 'Credits reclaimed today'), m.creditsReclaimedToday]]));
    content.append(panel(t("admin.recentLoginFailures", null, 'Recent login failures')).append(table([t("admin.time", null, 'Time'), t("common.username", null, 'Username'), t("admin.ip", null, 'IP'), t("admin.result", null, 'Result')], result.recentFailures,
      row => [fmt(row.createdAt), row.usernameAttempted, row.ipAddress, status(row.result)], ['timestamp', null, 'code', 'status'])));
    content.append(panel(t("admin.recentAdminActions", null, 'Recent admin actions')).append(table([t("admin.time", null, 'Time'), t("common.action", null, 'Action'), t("admin.actorID", null, 'Actor ID'), t("admin.summary", null, 'Summary')], result.recentActions,
      row => [fmt(row.createdAt), window.I18n.enumLabel(row.action, 'audit'), row.actorUserId, auditSummary(row.summary)], ['timestamp', null, 'code'])));
    content.append(panel(t("common.system", null, 'System')).append($('<p class="mb-0">').text('Veriqra ' + result.system.version
      + t('admin.systemApplicationDatabase', { application: display(result.system.applicationStatus),
        database: display(result.system.databaseStatus) }, ' · Application {application} · Database {database}'))));
  }
  async function renderUsers() {
    const result = await load('users' + query());
    const top = $('<div class="d-flex flex-wrap justify-content-between align-items-center gap-2 mb-3">')
      .append($('<p class="mb-0">').text(t("admin.manageAccountsWithoutDeletingHistoricalUsers", null, 'Manage accounts without deleting historical users.')),
        action(t("admin.createUser", null, 'Create user'), () => modal(t("admin.createUser", null, 'Create user'), [
          { name: 'username', label: t("common.username", null, 'Username'), required: true },
          { name: 'displayName', label: t("admin.displayName", null, 'Display name'), required: true },
          { name: 'password', label: t("admin.temporaryPassword12Characters", null, 'Temporary password (12+ characters)'), type: 'password', required: true },
          { name: 'systemRole', label: t("admin.systemRole", null, 'System role'), options: ['USER', 'ADMIN'] },
          { name: 'status', label: t("common.status", null, 'Status'), options: ['ACTIVE', 'DISABLED'] }
        ], t("admin.createUser", null, 'Create user'), values => post('users', values)), 'btn-primary'));
    content.empty().append(top, table([t("common.username", null, 'Username'), t("admin.displayName", null, 'Display name'), t("admin.role", null, 'Role'), t("common.status", null, 'Status'), t("admin.lastLogin", null, 'Last login'), t("admin.lastSeen", null, 'Last seen'), t("admin.ip", null, 'IP'), t("admin.device", null, 'Device'), t("admin.credits", null, 'Credits'), t("common.actions", null, 'Actions')], result.items, user => [
      user.username, user.displayName, display(user.systemRole), status(user.status), fmt(user.lastLogin), fmt(user.lastSeen), user.lastIp,
      display(user.lastDevice), number(user.creditBalance), $('<div class="actions">').append(
        action(t("common.edit", null, 'Edit'), () => modal(t('admin.editUser', { username: user.username }, 'Edit {username}'), [
          { name: 'displayName', label: t("admin.displayName", null, 'Display name'), value: user.displayName, required: true },
          { name: 'systemRole', label: t("admin.systemRole", null, 'System role'), options: ['USER', 'ADMIN'], value: user.systemRole },
          { name: 'status', label: t("common.status", null, 'Status'), options: ['ACTIVE', 'DISABLED'], value: user.status }
        ], t("common.save", null, 'Save'), values => api.action('PATCH', 'admin/users/' + user.id, { ...values, lockVersion: user.lockVersion }))),
        action(t("admin.resetPassword", null, 'Reset password'), () => modal(t('admin.resetPasswordFor', { username: user.username }, 'Reset password for {username}'),
          [{ name: 'password', label: t("admin.newPassword12Characters", null, 'New password (12+ characters)'), type: 'password', required: true }],
          t("admin.resetPassword", null, 'Reset password'), values => post('users/' + user.id + '/reset-password', values)), 'btn-outline-danger'),
        action(t("admin.credits", null, 'Credits'), () => viewUserCredits(user)),
        action(t("admin.grant", null, 'Grant'), () => creditChange({ userId: user.id, username: user.username }, 'grant')),
        action(t("admin.reclaim", null, 'Reclaim'), () => creditChange({ userId: user.id, username: user.username }, 'reclaim'), 'btn-outline-danger'))
    ], [null, null, null, 'status', 'timestamp', 'timestamp', 'code', null, 'numeric', 'actions']), paging(result, render));
  }
  async function viewUserCredits(user) {
    try {
      const result = await load('users/' + user.id + '/credits');
      const history = result.transactions.map(row => [fmt(row.createdAt), display(row.type), number(row.amount),
        t('admin.actorNumber', { id: row.actorUserId }, 'actor #{id}'), row.reason || '—'].join(' · ')).join('\n');
      modal(t('admin.userCredits', { username: user.username }, 'Experimental Credits · {username}'), [
        { name: 'balance', label: t("admin.currentBalance", null, 'Current balance'), value: result.account.balance, readonly: true },
        { name: 'history', label: t("admin.recent10LedgerEntries", null, 'Recent 10 ledger entries'), type: 'textarea', value: history || t("admin.noCreditChangesYet", null, 'No Credit changes yet.'), readonly: true }
      ], t("common.close", null, 'Close'), async () => null);
    } catch (error) { feedback(errorMessage(error), true); }
  }
  async function renderCredits() {
    const [result, summary] = await Promise.all([load('credits' + query()), load('credits/summary')]);
    const selected = state.creditSelection;
    const rowActions = account => $('<div class="actions">').append(
      action(t("admin.grant", null, 'Grant'), () => creditChange(account, 'grant')),
      action(t("admin.reclaim", null, 'Reclaim'), () => creditChange(account, 'reclaim'), 'btn-outline-danger'),
      action(t("admin.history", null, 'History'), () => { state.filters = { userId: String(account.userId) }; creditHistoryPage.page = 1; renderCreditHistory(); }));
    const batch = action(t("admin.batchGrant", null, 'Batch Grant'), () => {
      modal(t("admin.batchExperimentalCredits", null, 'Batch Experimental Credits'), [
        { name: 'scope', label: t("admin.recipients", null, 'Recipients'), options: ['SELECTED_USERS', 'ALL_ACTIVE_USERS'] },
        { name: 'amount', label: t("admin.amountPerUser", null, 'Amount per user'), type: 'number', min: 1, required: true },
        { name: 'reason', label: t("admin.reasonRecommended", null, 'Reason (recommended)') }
      ], t("admin.reviewAndGrant", null, 'Review and grant'), async values => {
        const all = values.scope === 'ALL_ACTIVE_USERS';
        const ids = [...selected.keys()];
        const preview = all ? await load('credits/recipients') : null;
        const recipients = all ? preview.length : ids.length;
        const amount = Number(values.amount);
        if (!Number.isSafeInteger(amount) || amount <= 0 || recipients <= 0 || recipients > 500)
          throw { message: t("admin.select1500RecipientsAndAPositiveWholeAmount", null, 'Select 1–500 recipients and a positive whole amount.') };
        const total = BigInt(values.amount) * BigInt(recipients);
        if (!window.confirm(t('admin.batchConfirm', { users: window.I18n.formatNumber(recipients),
          amount: window.I18n.formatCredit(amount), total: window.I18n.formatCredit(total) },
        'Experimental Credits\nUsers: {users}\nAmount per user: {amount}\nTotal issuance: {total}\nConfirm this batch?'))) return false;
        await post('credits/batch-grant', { scope: values.scope, userIds: all ? null : ids,
          expectedActiveUserIds: all ? preview : null,
          amount: amount, reason: values.reason || null });
        selected.clear();
      });
    }, 'btn-primary');
    const rows = result.items;
    content.empty().append($('<p class="text-secondary">').text(t("admin.experimentalInternalCreditsHaveNoMonetaryValueOrConsumptionFeature", null, 'Experimental internal Credits have no monetary value or consumption feature.')),
      cards([[t("admin.totalBalance", null, 'Total balance'), summary.totalBalance], [t("admin.totalIssued", null, 'Total issued'), summary.totalIssued],
        [t("admin.totalReclaimed", null, 'Total reclaimed'), summary.totalReclaimed], [t("admin.usersWithBalance", null, 'Users with balance'), summary.usersWithBalance]]), batch,
      $('<span class="ms-2" id="credit-selected-count" role="status">').text(t('admin.selectedCount', { count: selected.size }, '{count} selected')),
      action(t("admin.clearSelection", null, 'Clear selection'), () => { selected.clear(); render(); }, 'btn-outline-secondary'),
      $('<div class="mt-3">').append(table([t("admin.select", null, 'Select'), t("common.username", null, 'Username'), t("admin.displayName", null, 'Display name'), t("common.status", null, 'Status'), t("admin.balance", null, 'Balance'), t("admin.lastChange", null, 'Last change'), t("common.actions", null, 'Actions')], rows,
        account => [
          $('<input type="checkbox" class="form-check-input">').attr('aria-label', t('admin.selectRecipient', null, 'Select recipient'))
            .prop('checked', selected.has(account.userId)).on('change', function () {
              if (this.checked) selected.set(account.userId, account.username); else selected.delete(account.userId);
              $('#credit-selected-count').text(t('admin.selectedCount', { count: selected.size }, '{count} selected'));
            }), account.username, account.displayName, status(account.status), number(account.balance), fmt(account.updatedAt), rowActions(account)
        ], [null, null, null, 'status', 'numeric', 'timestamp', 'actions'])), paging(result, render), panel(t("admin.creditLedger", null, 'Credit ledger')).append($('<div id="credit-history">')));
    await renderCreditHistory();
  }
  function creditChange(account, kind) {
    modal(kind === 'grant' ? t('admin.grantToUser', { username: account.username }, 'Grant to {username}')
      : t('admin.reclaimFromUser', { username: account.username }, 'Reclaim from {username}'),
      [{ name: 'amount', label: t("admin.positiveCreditAmount", null, 'Positive Credit amount'), type: 'number', min: 1, required: true },
        { name: 'reason', label: t("admin.reasonRecommended", null, 'Reason (recommended)') }], kind === 'grant' ? t("admin.grant", null, 'Grant') : t("admin.reclaim", null, 'Reclaim'), async values => {
        const amount = Number(values.amount);
        if (!Number.isSafeInteger(amount) || amount <= 0) throw { message: t("admin.enterAPositiveWholeAmount", null, 'Enter a positive whole amount.') };
        if (kind === 'reclaim' && !window.confirm(t('admin.reclaimConfirm',
          { amount: window.I18n.formatCredit(amount), username: account.username },
          'Reclaim {amount} Credits from {username}?'))) return false;
        await post('users/' + account.userId + '/credits/' + kind, { amount: amount, reason: values.reason || null });
      });
  }
  async function renderCreditHistory() {
    const target = $('#credit-history'); if (!target.length) return;
    const filter = filters([['username', t("common.username", null, 'Username')], ['type', t("admin.type", null, 'Type'), null,
      ['GRANT', 'RECLAIM', 'PEER_TRANSFER_OUT', 'PEER_TRANSFER_IN', 'HANDOFF_OUT', 'HANDOFF_IN', 'TASK_REWARD']],
      ['actorId', t("admin.actorID", null, 'Actor ID')], ['batchId', t("admin.batchID", null, 'Batch ID')], ['from', t("admin.from", null, 'From'), 'datetime-local'], ['to', t("admin.to", null, 'To'), 'datetime-local']], renderCreditHistory, creditHistoryPage);
    const result = await load('credits/transactions' + query({ page: String(creditHistoryPage.page) }));
    target.empty().append(filter, table([t("admin.time", null, 'Time'), t("admin.userID", null, 'User ID'), t("admin.type", null, 'Type'), t("admin.amount", null, 'Amount'), t("admin.actorID", null, 'Actor ID'), t("admin.batchID", null, 'Batch ID'), t("admin.reason", null, 'Reason'), t('collab.project', null, 'Project ID'), t('collab.reference', null, 'Reference'), t('collab.counterparty', null, 'Counterparty')], result.items,
      row => [fmt(row.createdAt), row.userId, display(row.type), number(row.amount), row.actorUserId, row.batchId, row.reason,
        row.projectId, row.transferId || row.taskId, row.counterpartyUserId], ['timestamp', 'code', null, 'numeric', 'code', 'code', null, 'code', 'code', 'code']),
      paging(result, renderCreditHistory, creditHistoryPage));
  }
  async function renderLog(path, fields, headers, values, columnTypes) {
    const result = await load(path + query());
    content.empty().append(filters(fields, render), table(headers, result.items, values, columnTypes), paging(result, render));
  }
  async function renderSessions() {
    const result = await load('sessions' + query());
    content.empty().append($('<div class="alert alert-info">').text(t("admin.loginActivityOnlyLastSeenIsUserLevelRequestActivityNotALiveSessionTimestampNoOnlineSessionRegistryOrForceLogoutIsAvailable", null, 'Login activity only. Last seen is user-level request activity, not a live-session timestamp. No online-session registry or force logout is available.')),
      table([t("admin.loginTime", null, 'Login time'), t("admin.userLastSeen", null, 'User last seen'), t("admin.userID", null, 'User ID'), t("common.username", null, 'Username'), t("admin.ip", null, 'IP'), t("admin.device", null, 'Device')], result.activity.items,
        row => [fmt(row.loginTime), fmt(row.userLastSeen), row.userId, row.username, row.ipAddress, display(row.deviceType)], ['timestamp', 'timestamp', 'code', null, 'code']),
      paging(result.activity, render));
  }
  async function renderSecurity() {
    const result = await load('security'); const m = result.metrics;
    content.empty().append(cards([[t("admin.failedLoginsToday", null, 'Failed logins today'), m.failedLoginsToday], [t("admin.rateLimitedToday", null, 'Rate limited today'), m.rateLimitedToday],
      [t("admin.4xxToday", null, '4xx today'), m.clientErrorsToday], [t("admin.5xxToday", null, '5xx today'), m.serverErrorsToday], [t("admin.uniqueIPsToday", null, 'Unique IPs today'), m.uniqueIpsToday]]),
      panel(t("admin.recentFailures", null, 'Recent failures')).append(table([t("admin.time", null, 'Time'), t("common.username", null, 'Username'), t("admin.ip", null, 'IP'), t("admin.device", null, 'Device')], result.recentFailures,
        row => [fmt(row.createdAt), row.usernameAttempted, row.ipAddress, display(row.deviceType)], ['timestamp', null, 'code'])),
      panel(t("admin.recentRateLimits", null, 'Recent rate limits')).append(table([t("admin.time", null, 'Time'), t("common.username", null, 'Username'), t("admin.ip", null, 'IP')], result.recentRateLimits,
        row => [fmt(row.createdAt), row.usernameAttempted, row.ipAddress], ['timestamp', null, 'code'])));
  }
  async function renderSystem() {
    const result = await load('system'); const dl = $('<dl class="admin-detail">');
    Object.entries(result).forEach(([key, value]) => dl.append($('<div>').append(
      $('<dt>').text(t('system.' + key, null, key)), $('<dd>').attr({
        'data-vq-type': key === 'buildCommit' || key === 'veriqraVersion' || key === 'databaseVersion' || key === 'publicOrigin' ? 'code'
          : key === 'databaseTableCount' || key === 'uptimeSeconds' ? 'numeric' : key === 'buildTime' || key === 'serverTime' ? 'timestamp' : null,
        'data-status': key.endsWith('Status') ? value : null,
        'data-vq-tone': key.endsWith('Status') ? statusTone(value) : null
      }).addClass(key.endsWith('Status') ? 'status-badge' : '').text(key.endsWith('Status') ? display(value)
        : key === 'sessionSecureMode' ? t(value ? 'system.enabled' : 'system.disabled')
          : key === 'publicOrigin' && value === 'Not configured' ? t('system.notConfigured')
            : key === 'applicationTimeZone' ? t('system.displayTimeZone', null, value)
              : key === 'databaseTableCount' || key === 'uptimeSeconds' ? window.I18n.formatNumber(value) : fmt(value)))));
    content.empty().append(panel(t("admin.readOnlySystemInformation", null, 'Read-only system information')).append(dl));
  }
  async function render() {
    const token = ++state.generation;
    content.empty().append($('<p role="status">').text(t("common.loading", null, 'Loading…'))); feedback('');
    try {
      switch (pageName) {
        case 'index': await renderDashboard(); break;
        case 'users': await renderUsers(); break;
        case 'credits': await renderCredits(); break;
        case 'login-history': await renderLog('login-history',
          [['from', t("admin.from", null, 'From'), 'datetime-local'], ['to', t("admin.to", null, 'To'), 'datetime-local'], ['username', t("common.username", null, 'Username')],
            ['result', t("admin.result", null, 'Result'), null, ['SUCCESS', 'FAILURE', 'RATE_LIMITED']], ['ip', t("admin.ip", null, 'IP')],
            ['deviceType', t("admin.device", null, 'Device'), null, ['Desktop', 'Mobile', 'Tablet', 'Other']]],
          [t("admin.time", null, 'Time'), t("common.username", null, 'Username'), t("admin.result", null, 'Result'), t("admin.ip", null, 'IP'), t("admin.browser", null, 'Browser'), 'OS', t("admin.device", null, 'Device')],
          row => [fmt(row.createdAt), row.usernameAttempted, status(row.result), row.ipAddress, row.browser, row.operatingSystem, display(row.deviceType)], ['timestamp', null, 'status', 'code']); break;
        case 'access-logs': await renderLog('access-logs',
          [['from', t("admin.from", null, 'From'), 'datetime-local'], ['to', t("admin.to", null, 'To'), 'datetime-local'], ['username', t("common.username", null, 'Username')], ['ip', t("admin.ip", null, 'IP')],
            ['status', t("common.status", null, 'Status')], ['method', t("admin.method", null, 'Method')], ['deviceType', t("admin.deviceType", null, 'Device Type'), null, ['Desktop', 'Mobile', 'Tablet', 'Other']]],
          [t("admin.time", null, 'Time'), t('admin.user', null, 'User'), t("admin.ip", null, 'IP'), t("admin.operatingSystem", null, 'Operating System'), t("admin.method", null, 'Method'), t("admin.path", null, 'Path'), t("common.status", null, 'Status'), t("admin.requestID", null, 'Request ID'), t("admin.details", null, 'Details')],
          row => [fmt(row.createdAt), row.username || (row.userId ? '#' + row.userId : t("admin.guest", null, 'Guest')), row.ipAddress, operatingSystem(row.operatingSystem), row.httpMethod, row.requestPath,
            httpStatus(row.statusCode), row.requestId, action(t("common.view", null, 'View'), () => modal(t("admin.accessDetails", null, 'Access details'), [
              { name: 'browser', label: t("admin.browser", null, 'Browser'), value: row.browser, readonly: true }, { name: 'os', label: t("admin.operatingSystem", null, 'Operating System'), value: operatingSystem(row.operatingSystem), readonly: true },
              { name: 'device', label: t("admin.device", null, 'Device'), value: display(row.deviceType), readonly: true }, { name: 'agent', label: t("admin.fullUserAgent", null, 'Full User-Agent'), value: row.userAgent, readonly: true },
              { name: 'requestId', label: t("admin.requestID", null, 'Request ID'), value: row.requestId, readonly: true }
            ], t("common.close", null, 'Close'), async () => null))], ['timestamp', null, 'code', null, 'code', 'code', 'status', 'code', 'actions']); break;
        case 'audit-log': await renderLog('audit-logs',
          [['from', t("admin.from", null, 'From'), 'datetime-local'], ['to', t("admin.to", null, 'To'), 'datetime-local'], ['actorId', t("admin.actorID", null, 'Actor ID')], ['action', t("common.action", null, 'Action')]],
          [t("admin.time", null, 'Time'), t("admin.actorID", null, 'Actor ID'), t("common.action", null, 'Action'), t("admin.target", null, 'Target'), t("admin.summary", null, 'Summary'), t("admin.requestID", null, 'Request ID')],
          row => [fmt(row.createdAt), row.actorUserId, window.I18n.enumLabel(row.action, 'audit'), row.targetType + ' #' + fmt(row.targetId), auditSummary(row.summary), row.requestId], ['timestamp', 'code', null, 'code', null, 'code']); break;
        case 'sessions': await renderSessions(); break;
        case 'security': await renderSecurity(); break;
        case 'system': await renderSystem(); break;
      }
    } catch (error) { if (token === state.generation) { content.empty().append($('<p class="empty-state">').text(t("admin.unableToLoadThisPage", null, 'Unable to load this page.'))); feedback(errorMessage(error), true); } }
  }
  $('#admin-menu').on('click', function () {
    const opened = $('#admin-sidebar').toggleClass('open').hasClass('open'); $(this).attr('aria-expanded', String(opened));
  });
  $('#admin-logout').on('click', async function () {
    try { await api.post('auth/logout'); location.replace(new URL('login.html', document.baseURI).href); }
    catch (error) { feedback(errorMessage(error), true); }
  });
  if (!pages.includes(pageName)) { content.text(t("admin.unknownAdministrationPage", null, 'Unknown administration page.')); return; }
  $('[data-admin-nav="' + pageName + '"]').addClass('active').attr('aria-current', 'page');
  window.I18n.init().then(function () {
    api.get('auth/me').done(user => {
      $('#admin-user').text(user.username);
      if (user.systemRole !== 'ADMIN') { forbidden = true; showForbidden(); return; }
      authorized = true;
      render();
    }).fail(error => { if (error.status !== 401) feedback(errorMessage(error), true); });
  });
  document.addEventListener('veriqra:localechange', function () {
    document.querySelectorAll('.modal.show').forEach(function (element) { bootstrap.Modal.getInstance(element)?.hide(); });
    if (authorized) render(); else if (forbidden) showForbidden();
  });
})(jQuery, window.VeriqraApi);
