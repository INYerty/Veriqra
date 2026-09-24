(function ($, api) {
  'use strict';
  // app.js owns the session, selected project and view. Every asynchronous render is scoped to its generation.
  let projectId = null;
  let view = 'dashboard';
  let generation = 0;
  let detail = null;
  let runCaseId = null;
  let editing = false;
  let pendingAttempt = null;
  const active = function () { return view === 'test-plans' || view === 'runs'; };
  const valid = function (token) { return token === generation && !!projectId && active(); };
  const base = function (resource) { return 'projects/' + encodeURIComponent(projectId) + '/' + resource; };
  const path = function (id) { return base(view === 'test-plans' ? 'test-plans' : 'runs') + '/' + encodeURIComponent(id); };
  const label = function (value) { return String(value || '').replaceAll('_', ' '); };
  const date = function (value) { return value ? String(value).replace('T', ' ') : '—'; };
  const planKey = function (plan) { return 'TP-' + String(plan.keyNo).padStart(3, '0'); };

  function notice(message, error) {
    $('#execution-notice').text(message || '').toggleClass('d-none', !message)
      .toggleClass('alert-danger', !!error).toggleClass('alert-success', !!message && !error);
  }
  function formError(message) { $('#execution-form-error').text(message || '').toggleClass('d-none', !message); }
  function attemptError(message) { $('#execution-attempt-error').text(message || '').toggleClass('d-none', !message); }
  function conflict(failure) {
    return failure.status === 409
      ? 'This record changed or cannot enter the requested state. Reload its latest details before trying again.'
      : failure.message;
  }
  function badge(status) {
    const colors = { DRAFT: 'secondary', READY: 'success', ARCHIVED: 'secondary', IN_PROGRESS: 'primary', COMPLETED: 'success', CANCELLED: 'secondary', NOT_RUN: 'secondary', PASS: 'success', FAIL: 'danger', BLOCKED: 'warning', SKIPPED: 'info' };
    return $('<span>').addClass('badge text-bg-' + (colors[status] || 'secondary')).text(label(status));
  }
  function field(name, value) {
    return $('<div class="asset-field">').append($('<dt>').text(name), $('<dd>').text(value == null || value === '' ? '—' : String(value)));
  }
  function reset() {
    generation++;
    detail = null;
    runCaseId = null;
    editing = false;
    pendingAttempt = null;
    $('#execution-list, #execution-detail, #execution-actions, #execution-items, #execution-case-selector, #execution-snapshot, #execution-history').empty();
    $('#execution-list-status, #execution-items-status, #execution-history-status').text('');
    $('#execution-list-panel, #execution-detail-panel, #execution-form, #execution-case-panel').addClass('d-none');
    $('#execution-save, #execution-submit').prop('disabled', false);
    notice(''); formError(''); attemptError('');
  }
  function panels(which) {
    $('#execution-list-panel').toggleClass('d-none', which !== 'list');
    $('#execution-detail-panel').toggleClass('d-none', which !== 'detail');
    $('#execution-form').toggleClass('d-none', which !== 'form');
    $('#execution-case-panel').toggleClass('d-none', which !== 'case');
  }
  function caseSelector(rows, chosen) {
    const target = $('#execution-case-selector').empty();
    if (!rows.length) { target.append($('<p class="text-secondary">').text('No eligible test cases in this project.')); return; }
    rows.forEach(function (row) {
      const id = 'execution-select-case-' + row.id;
      target.append($('<div class="form-check">').append(
        $('<input type="checkbox" class="form-check-input">').attr('id', id).val(String(row.id)).prop('checked', chosen.includes(String(row.id))),
        $('<label class="form-check-label">').attr('for', id).text('TC-' + String(row.keyNo).padStart(3, '0') + ' · ' + row.title + ' · ' + label(row.status))));
    });
  }
  function selectedCases() {
    return $('#execution-case-selector input:checked').map(function () { return Number(this.value); }).get();
  }

  async function list() {
    if (!projectId || !active()) return;
    const token = ++generation;
    detail = null; runCaseId = null; pendingAttempt = null;
    panels('list'); notice('');
    $('#execution-list-title').text(view === 'test-plans' ? 'Test plans' : 'Test runs');
    $('#execution-new').text(view === 'test-plans' ? 'Create plan' : 'Create run');
    $('#execution-list').empty(); $('#execution-list-status').text('Loading…');
    try {
      const rows = await api.get(base(view === 'test-plans' ? 'test-plans' : 'runs'));
      if (!valid(token)) return;
      $('#execution-list-status').text(rows.length ? rows.length + ' item(s)' : 'No items yet.');
      rows.forEach(function (row) {
        const title = view === 'test-plans' ? planKey(row) + ' · ' + row.name : row.name;
        const meta = $('<div class="asset-list-meta">').append(badge(row.status),
          $('<span>').text(view === 'runs' ? (row.testPlanId == null ? 'Ad-hoc' : 'Plan-based') : 'v' + row.version),
          $('<small>').text(date(row.createdAt)));
        $('#execution-list').append($('<div class="asset-list-row">').append(
          $('<div class="asset-list-summary">').append($('<strong>').text(title),
            $('<p class="mb-1 text-secondary">').text(view === 'test-plans' ? (row.description || 'No description') : (row.environment || 'No environment'))),
          meta, $('<button type="button" class="btn btn-outline-primary btn-sm">').text('View ' + title)
            .on('click', function () { openDetail(row.id); })));
      });
    } catch (failure) { if (valid(token)) { $('#execution-list-status').text(''); notice(failure.message, true); } }
  }

  async function openDetail(id) {
    const token = ++generation;
    detail = null; runCaseId = null; pendingAttempt = null;
    panels('detail'); notice('');
    $('#execution-detail-title').text('Loading…');
    $('#execution-detail, #execution-actions, #execution-items').empty();
    $('#execution-items-status').text('');
    try {
      const current = await api.get(path(id));
      if (!valid(token)) return;
      detail = current;
      if (view === 'test-plans') await renderPlan(current, token);
      else renderRun(current);
    } catch (failure) { if (valid(token)) notice(failure.message, true); }
  }
  async function renderPlan(current, token) {
    const plan = current.plan;
    $('#execution-detail-title').text(planKey(plan) + ' · ' + plan.name);
    $('#execution-detail').append($('<dl class="asset-fields">').append(
      field('Description', plan.description), field('Status', label(plan.status)),
      field('Version', plan.version), field('Created', date(plan.createdAt)), field('Updated', date(plan.updatedAt))));
    $('#execution-items-title').text('Included test cases (' + current.testCaseIds.length + ')');
    $('#execution-items-status').text('Loading current case labels…');
    const cases = await api.get(base('test-cases'));
    if (!valid(token) || detail !== current) return;
    $('#execution-items-status').text('');
    const byId = new Map(cases.map(function (item) { return [String(item.id), item]; }));
    if (!current.testCaseIds.length) $('#execution-items').append($('<p class="text-secondary">').text('No cases in this plan yet.'));
    current.testCaseIds.forEach(function (id) {
      const item = byId.get(String(id));
      const title = item ? 'TC-' + String(item.keyNo).padStart(3, '0') + ' · ' + item.title : 'Case #' + id;
      const row = $('<div class="asset-link-row">').append($('<span>').text(title), item ? badge(item.status) : $('<span>').text('Unavailable'));
      if (plan.status !== 'ARCHIVED') row.append($('<button type="button" class="btn btn-outline-danger btn-sm">').text('Remove from plan')
        .on('click', function () { changeMember(plan, id, title, 'remove'); }));
      $('#execution-items').append(row);
    });
    if (plan.status === 'ARCHIVED') return;
    $('#execution-actions').append($('<button type="button" class="btn btn-outline-primary">').text('Edit plan')
      .on('click', function () { showForm(true); }));
    $('#execution-actions').append($('<button type="button" class="btn btn-outline-danger">').text('Archive plan')
      .on('click', function () { archivePlan(plan); }));
    const available = cases.filter(function (item) { return item.status !== 'ARCHIVED' && !current.testCaseIds.includes(item.id); });
    if (available.length) {
      const select = $('<select class="form-select execution-inline-select" aria-label="Test case to add">');
      available.forEach(function (item) { select.append($('<option>').val(item.id).text('TC-' + String(item.keyNo).padStart(3, '0') + ' · ' + item.title + ' · ' + label(item.status))); });
      $('#execution-actions').append(select, $('<button type="button" class="btn btn-outline-primary">').text('Add case to plan')
        .on('click', function () { changeMember(plan, Number(select.val()), select.find(':selected').text(), 'add'); }));
    }
  }
  async function changeMember(plan, caseId, title, action) {
    if (action === 'remove' && !window.confirm('Remove "' + title + '" from plan "' + plan.name + '"?')) return;
    const token = generation;
    $('#execution-actions button, #execution-items button').prop('disabled', true);
    try {
      await api.post(path(plan.id) + '/test-cases/' + caseId + (action === 'remove' ? '/remove' : ''), { expectedVersion: plan.version });
      if (valid(token)) await openDetail(plan.id);
    } catch (failure) { if (valid(token)) notice(conflict(failure), true); }
    finally { if (valid(token)) $('#execution-actions button, #execution-items button').prop('disabled', false); }
  }
  async function archivePlan(plan) {
    if (!window.confirm('Archive plan "' + plan.name + '"? It will become read-only.')) return;
    const token = generation;
    $('#execution-actions button').prop('disabled', true);
    try { await api.post(path(plan.id) + '/archive', { expectedVersion: plan.version }); if (valid(token)) await openDetail(plan.id); }
    catch (failure) { if (valid(token)) notice(conflict(failure), true); }
    finally { if (valid(token)) $('#execution-actions button').prop('disabled', false); }
  }
  function renderRun(current) {
    const run = current.run;
    $('#execution-detail-title').text(run.name);
    $('#execution-detail').append($('<dl class="asset-fields">').append(
      field('Status', label(run.status)), field('Source', run.testPlanId == null ? 'Ad-hoc' : 'Plan #' + run.testPlanId),
      field('Environment', run.environment), field('Build', run.buildVersion),
      field('Created', date(run.createdAt)), field('Ended', date(run.endedAt))));
    $('#execution-items-title').text('Run case snapshots (' + current.cases.length + ')');
    current.cases.forEach(function (item) {
      $('#execution-items').append($('<div class="asset-list-row">').append(
        $('<div class="asset-list-summary">').append($('<strong>').text(item.snapshotTitle),
          $('<p class="mb-1 text-secondary">').text('Frozen case #' + item.testCaseId + ' · captured ' + date(item.capturedAt))),
        badge(item.currentOutcome), $('<button type="button" class="btn btn-outline-primary btn-sm">').text('View snapshot and attempts')
          .on('click', function () { openCase(item.runCaseId); })));
    });
    if (run.status === 'IN_PROGRESS') {
      [['Complete run', 'complete'], ['Cancel run', 'cancel']].forEach(function (entry) {
        $('#execution-actions').append($('<button type="button" class="btn btn-outline-secondary">').text(entry[0])
          .on('click', function () { finishRun(run, entry[1]); }));
      });
    }
  }
  async function finishRun(run, action) {
    if (!window.confirm((action === 'complete' ? 'Complete' : 'Cancel') + ' run "' + run.name + '"?')) return;
    const token = generation;
    $('#execution-actions button').prop('disabled', true);
    try { await api.post(path(run.id) + '/' + action, { expectedVersion: run.version }); if (valid(token)) await openDetail(run.id); }
    catch (failure) { if (valid(token)) notice(conflict(failure), true); }
    finally { if (valid(token)) $('#execution-actions button').prop('disabled', false); }
  }
  function snapshot(item) {
    const target = $('#execution-snapshot').empty();
    target.append($('<dl class="asset-fields">').append(
      field('Title at capture', item.snapshotTitle), field('Priority at capture', label(item.snapshotPriority)),
      field('Description at capture', item.snapshotDescription), field('Preconditions at capture', item.snapshotPreconditions),
      field('Captured', date(item.capturedAt)), field('Current outcome', label(item.currentOutcome))));
    target.append($('<h3 class="fs-5 mt-3">').text('Snapshot steps'));
    item.steps.forEach(function (step) {
      target.append($('<div class="asset-step">').append($('<strong>').text('Step ' + step.stepOrder),
        $('<div>').append($('<span class="text-secondary">').text('Action: '), document.createTextNode(step.action)),
        $('<div>').append($('<span class="text-secondary">').text('Expected: '), document.createTextNode(step.expectedResult))));
    });
  }
  async function openCase(id) {
    if (!detail || view !== 'runs') return;
    const runId = detail.run.id;
    const token = ++generation;
    runCaseId = id; pendingAttempt = null;
    panels('case'); notice(''); attemptError('');
    $('#execution-case-title').text('Loading snapshot…');
    $('#execution-snapshot, #execution-history').empty();
    $('#execution-history-status').text('Loading attempt history…');
    $('#execution-attempt-form').addClass('d-none');
    try {
      const current = await api.get(path(runId));
      if (!valid(token) || runCaseId !== id) return;
      const item = current.cases.find(function (row) { return row.runCaseId === id; });
      if (!item) throw { message: 'Run case is no longer available.' };
      const history = await api.get(path(runId) + '/cases/' + encodeURIComponent(id) + '/attempts');
      if (!valid(token) || runCaseId !== id) return;
      detail = current;
      $('#execution-case-title').text('Snapshot · ' + item.snapshotTitle);
      snapshot(item);
      const target = $('#execution-history').empty();
      $('#execution-history-status').text(history.length ? history.length + ' recorded attempt(s)' : 'No attempts yet.');
      history.forEach(function (attempt, index) {
        const card = $('<div class="execution-attempt">').append(
          $('<div class="asset-panel-heading mb-1">').append($('<strong>').text('Attempt #' + attempt.attemptNo + (index === history.length - 1 ? ' · latest' : '')), badge(attempt.outcome)),
          $('<dl class="asset-fields mb-0">').append(field('Executed', date(attempt.executedAt)), field('Actor ID', attempt.executedBy),
            field('Duration (ms)', attempt.durationMs), field('Actual result / comment', attempt.comment), field('Failure message', attempt.failureMessage)));
        target.append(card);
      });
      $('#execution-attempt-form').toggleClass('d-none', current.run.status !== 'IN_PROGRESS');
      $('#execution-attempt-form')[0].reset();
      $('#execution-failure-wrap').addClass('d-none');
      $('#execution-submit').prop('disabled', false).text(history.length ? 'Record retest as new attempt' : 'Record new attempt');
    } catch (failure) { if (valid(token)) { $('#execution-history-status').text(''); notice(failure.message, true); } }
  }

  async function showForm(update) {
    if (!projectId || !active()) return;
    const token = ++generation;
    editing = update;
    panels('form'); notice(''); formError('');
    $('#execution-form')[0].reset();
    $('#execution-save').prop('disabled', true);
    $('#execution-form-title').text(view === 'test-plans' ? (update ? 'Edit plan' : 'Create plan') : 'Create run');
    $('#execution-description-wrap').toggleClass('d-none', view !== 'test-plans');
    $('#execution-status-wrap').toggleClass('d-none', view !== 'test-plans' || !update);
    $('#execution-run-fields').toggleClass('d-none', view !== 'runs');
    $('#execution-cases-fieldset').toggleClass('d-none', !!update);
    $('#execution-case-selector, #execution-plan').empty();
    $('#execution-name').val(update ? detail.plan.name : '');
    $('#execution-description').val(update ? detail.plan.description || '' : '');
    if (update) $('#execution-status').val(detail.plan.status);
    try {
      if (!update) {
        const cases = await api.get(base('test-cases'));
        if (!valid(token)) return;
        caseSelector(cases.filter(function (item) { return view === 'test-plans' ? item.status !== 'ARCHIVED' : item.status === 'READY'; }), []);
        if (view === 'runs') {
          const plans = await api.get(base('test-plans'));
          if (!valid(token)) return;
          plans.filter(function (plan) { return plan.status === 'READY'; }).forEach(function (plan) {
            $('#execution-plan').append($('<option>').val(plan.id).text(planKey(plan) + ' · ' + plan.name));
          });
          if (!$('#execution-plan option').length) $('#execution-plan').append($('<option>').val('').text('No READY plan available'));
          $('#execution-origin').val($('#execution-plan option[value!=""]').length ? 'plan' : 'adhoc');
          updateRunOrigin();
        }
      }
      if (valid(token)) { $('#execution-save').prop('disabled', false); $('#execution-name').trigger('focus'); }
    } catch (failure) { if (valid(token)) formError(failure.message); }
  }
  function updateRunOrigin() {
    const fromPlan = $('#execution-origin').val() === 'plan';
    $('#execution-plan-wrap').toggleClass('d-none', !fromPlan);
    $('#execution-cases-fieldset').toggleClass('d-none', fromPlan);
  }

  $(document).on('veriqra:project', function (event) {
    reset();
    projectId = event.originalEvent.detail.project ? String(event.originalEvent.detail.project.id) : null;
    if (projectId && active()) list();
  });
  $(document).on('veriqra:view', function (event) {
    const next = event.originalEvent.detail.view;
    if (next !== view) { reset(); view = next; }
    if (projectId && active()) list();
  });
  $('#execution-origin').on('change', updateRunOrigin);
  $('#execution-outcome').on('change', function () { $('#execution-failure-wrap').toggleClass('d-none', this.value !== 'FAIL'); });
  $('#execution-back').on('click', list);
  $('#execution-case-back').on('click', function () { if (detail) openDetail(detail.run.id); });
  $('#execution-new').on('click', function () { showForm(false); });
  $('#execution-cancel').on('click', function () { if (editing && detail) openDetail(detail.plan.id); else list(); });
  $('#execution-form').on('submit', async function (event) {
    event.preventDefault();
    if ($('#execution-save').prop('disabled') || !this.reportValidity()) return;
    const token = generation;
    const button = $('#execution-save').prop('disabled', true);
    formError('');
    const name = $('#execution-name').val().trim();
    if (!name) { formError('Name is required.'); button.prop('disabled', false); return; }
    let body;
    if (view === 'test-plans') {
      body = { name: name, description: $('#execution-description').val().trim() || null };
      if (editing) { body.status = $('#execution-status').val(); body.expectedVersion = detail.plan.version; }
      else body.testCaseIds = selectedCases();
    } else {
      body = { name: name, environment: $('#execution-environment').val().trim() || null,
        buildVersion: $('#execution-build').val().trim() || null };
      if ($('#execution-origin').val() === 'plan') {
        if (!$('#execution-plan').val()) { formError('Select a READY plan.'); button.prop('disabled', false); return; }
        body.testPlanId = Number($('#execution-plan').val());
      } else {
        body.testCaseIds = selectedCases();
        if (!body.testCaseIds.length) { formError('Select at least one READY test case.'); button.prop('disabled', false); return; }
      }
    }
    try {
      const saved = editing ? await api.put(path(detail.plan.id), body)
        : await api.post(base(view === 'test-plans' ? 'test-plans' : 'runs'), body);
      if (valid(token)) await openDetail(saved.id);
    } catch (failure) { if (valid(token)) formError(conflict(failure)); }
    finally { if (valid(token)) button.prop('disabled', false); }
  });
  $('#execution-attempt-form').on('submit', async function (event) {
    event.preventDefault();
    if ($('#execution-submit').prop('disabled') || !this.reportValidity() || !detail || runCaseId == null) return;
    const token = generation;
    const runId = detail.run.id;
    const body = { outcome: $('#execution-outcome').val(), durationMs: $('#execution-duration').val() === '' ? null : Number($('#execution-duration').val()),
      comment: $('#execution-comment').val().trim() || null,
      failureMessage: $('#execution-outcome').val() === 'FAIL' ? ($('#execution-failure').val().trim() || null) : null };
    if (body.durationMs != null && (!Number.isSafeInteger(body.durationMs) || body.durationMs < 0)) { attemptError('Duration must be a non-negative whole number.'); return; }
    const signature = JSON.stringify(body);
    if (!pendingAttempt || pendingAttempt.signature !== signature) pendingAttempt = { signature: signature, body: Object.assign({}, body, { submissionKey: crypto.randomUUID() }) };
    const button = $('#execution-submit').prop('disabled', true);
    attemptError('');
    try {
      await api.post(path(runId) + '/cases/' + encodeURIComponent(runCaseId) + '/attempts', pendingAttempt.body);
      if (valid(token)) { pendingAttempt = null; await openCase(runCaseId); }
    } catch (failure) { if (valid(token)) attemptError(conflict(failure) + ' Retrying unchanged values reuses this attempt key.'); }
    finally { if (valid(token)) button.prop('disabled', false); }
  });
})(jQuery, window.VeriqraApi);
