(function ($, api) {
  'use strict';
  const t = window.I18n.t;
  // app.js owns the session, selected project and view. Every asynchronous render is scoped to its generation.
  let projectId = null;
  let view = 'dashboard';
  let generation = 0;
  let detail = null;
  let runCaseId = null;
  let editing = false;
  let pendingAttempt = null;
  let caseOptions = [];
  const active = function () { return view === 'test-plans' || view === 'runs'; };
  const valid = function (token) { return token === generation && !!projectId && active(); };
  const base = function (resource) { return 'projects/' + encodeURIComponent(projectId) + '/' + resource; };
  const path = function (id) { return base(view === 'test-plans' ? 'test-plans' : 'runs') + '/' + encodeURIComponent(id); };
  const label = function (value) { return window.I18n.enumLabel(value); };
  const date = function (value) { return window.I18n.formatDateTime(value); };
  const planKey = function (plan) { return 'TP-' + String(plan.keyNo).padStart(3, '0'); };

  function notice(message, error) {
    $('#execution-notice').text(message || '').toggleClass('d-none', !message)
      .toggleClass('alert-danger', !!error).toggleClass('alert-success', !!message && !error);
  }
  function formError(message) { $('#execution-form-error').text(message || '').toggleClass('d-none', !message); }
  function attemptError(message) { $('#execution-attempt-error').text(message || '').toggleClass('d-none', !message); }
  function conflict(failure) {
    return failure.status === 409
      ? t('execution.conflict', null, 'This record changed or cannot enter the requested state. Reload its latest details before trying again.')
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
    caseOptions = [];
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
    if (!rows.length) { target.append($('<p class="text-secondary">').text(t("execution.noEligibleTestCasesInThisProject", null, 'No eligible test cases in this project.'))); return; }
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
    $('#execution-list-title').text(view === 'test-plans' ? t("common.testPlans", null, 'Test plans') : t("common.testRuns", null, 'Test runs'));
    $('#execution-new').text(view === 'test-plans' ? t("execution.createPlan", null, 'Create plan') : t("execution.createRun", null, 'Create run'));
    $('#execution-list').empty(); $('#execution-list-status').text(t("common.loading", null, 'Loading…'));
    try {
      const rows = await api.get(base(view === 'test-plans' ? 'test-plans' : 'runs'));
      if (!valid(token)) return;
      $('#execution-list-status').text(rows.length ? t('execution.itemCount', { count: window.I18n.formatNumber(rows.length) }, '{count} item(s)') : t("execution.noItemsYet", null, 'No items yet.'));
      rows.forEach(function (row) {
        const title = view === 'test-plans' ? planKey(row) + ' · ' + row.name : row.name;
        const meta = $('<div class="asset-list-meta">').append(badge(row.status),
          $('<span>').text(view === 'runs' ? (row.testPlanId == null ? t("execution.adHoc", null, 'Ad-hoc') : t("execution.planBased", null, 'Plan-based')) : 'v' + row.version),
          $('<small>').text(date(row.createdAt)));
        $('#execution-list').append($('<div class="asset-list-row">').append(
          $('<div class="asset-list-summary">').append($('<strong>').text(title),
            $('<p class="mb-1 text-secondary">').text(view === 'test-plans' ? (row.description || t("common.noDescription", null, 'No description')) : (row.environment || t("execution.noEnvironment", null, 'No environment')))),
          meta, $('<button type="button" class="btn btn-outline-primary btn-sm">').text(t('execution.viewItem', { title: title }, 'View {title}'))
            .on('click', function () { openDetail(row.id); })));
      });
    } catch (failure) { if (valid(token)) { $('#execution-list-status').text(''); notice(failure.message, true); } }
  }

  async function openDetail(id) {
    const token = ++generation;
    detail = null; runCaseId = null; pendingAttempt = null;
    panels('detail'); notice('');
    $('#execution-detail-title').text(t("common.loading", null, 'Loading…'));
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
      field(t("common.description", null, 'Description'), plan.description), field(t("common.status", null, 'Status'), label(plan.status)),
      field(t("common.version", null, 'Version'), plan.version), field(t("common.created", null, 'Created'), date(plan.createdAt)), field(t("common.updated", null, 'Updated'), date(plan.updatedAt))));
    $('#execution-items-title').text(t('execution.includedCases', { count: window.I18n.formatNumber(current.testCaseIds.length) }, 'Included test cases ({count})'));
    $('#execution-items-status').text(t('execution.loadingCaseLabels', null, 'Loading current case labels…'));
    const cases = await api.get(base('test-cases'));
    if (!valid(token) || detail !== current) return;
    $('#execution-items-status').text('');
    const byId = new Map(cases.map(function (item) { return [String(item.id), item]; }));
    if (!current.testCaseIds.length) $('#execution-items').append($('<p class="text-secondary">').text(t("execution.noCasesInThisPlanYet", null, 'No cases in this plan yet.')));
    current.testCaseIds.forEach(function (id) {
      const item = byId.get(String(id));
      const title = item ? 'TC-' + String(item.keyNo).padStart(3, '0') + ' · ' + item.title
        : t('execution.caseNumber', { id: id }, 'Case #{id}');
      const row = $('<div class="asset-link-row">').append($('<span>').text(title), item ? badge(item.status) : $('<span>').text(t("common.unavailable", null, 'Unavailable')));
      if (plan.status !== 'ARCHIVED') row.append($('<button type="button" class="btn btn-outline-danger btn-sm">').text(t("execution.removeFromPlan", null, 'Remove from plan'))
        .on('click', function () { changeMember(plan, id, title, 'remove'); }));
      $('#execution-items').append(row);
    });
    if (plan.status === 'ARCHIVED') return;
    $('#execution-actions').append($('<button type="button" class="btn btn-outline-primary">').text(t("execution.editPlan", null, 'Edit plan'))
      .on('click', function () { showForm(true); }));
    $('#execution-actions').append($('<button type="button" class="btn btn-outline-danger">').text(t("execution.archivePlan", null, 'Archive plan'))
      .on('click', function () { archivePlan(plan); }));
    const available = cases.filter(function (item) { return item.status !== 'ARCHIVED' && !current.testCaseIds.includes(item.id); });
    if (available.length) {
      const select = $('<select class="form-select execution-inline-select">').attr('aria-label', t('execution.testCaseToAdd', null, 'Test case to add'));
      available.forEach(function (item) { select.append($('<option>').val(item.id).text('TC-' + String(item.keyNo).padStart(3, '0') + ' · ' + item.title + ' · ' + label(item.status))); });
      $('#execution-actions').append(select, $('<button type="button" class="btn btn-outline-primary">').text(t("execution.addCaseToPlan", null, 'Add case to plan'))
        .on('click', function () { changeMember(plan, Number(select.val()), select.find(':selected').text(), 'add'); }));
    }
  }
  async function changeMember(plan, caseId, title, action) {
    if (action === 'remove' && !window.confirm(t('execution.removeFromPlanConfirm',
      { title: title, plan: plan.name }, 'Remove "{title}" from plan "{plan}"?'))) return;
    const token = generation;
    $('#execution-actions button, #execution-items button').prop('disabled', true);
    try {
      await api.post(path(plan.id) + '/test-cases/' + caseId + (action === 'remove' ? '/remove' : ''), { expectedVersion: plan.version });
      if (valid(token)) await openDetail(plan.id);
    } catch (failure) { if (valid(token)) notice(conflict(failure), true); }
    finally { if (valid(token)) $('#execution-actions button, #execution-items button').prop('disabled', false); }
  }
  async function archivePlan(plan) {
    if (!window.confirm(t('execution.archivePlanConfirm', { plan: plan.name },
      'Archive plan "{plan}"? It will become read-only.'))) return;
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
      field(t("common.status", null, 'Status'), label(run.status)), field(t("execution.source", null, 'Source'), run.testPlanId == null ? t("execution.adHoc", null, 'Ad-hoc') : t('execution.planNumber', { number: run.testPlanId }, 'Plan #{number}')),
      field(t("execution.environment", null, 'Environment'), run.environment), field(t("execution.build", null, 'Build'), run.buildVersion),
      field(t("common.created", null, 'Created'), date(run.createdAt)), field(t("execution.ended", null, 'Ended'), date(run.endedAt))));
    $('#execution-items-title').text(t('execution.runCaseSnapshots', { count: window.I18n.formatNumber(current.cases.length) }, 'Run case snapshots ({count})'));
    current.cases.forEach(function (item) {
      $('#execution-items').append($('<div class="asset-list-row">').append(
        $('<div class="asset-list-summary">').append($('<strong>').text(item.snapshotTitle),
          $('<p class="mb-1 text-secondary">').text(t('execution.frozenCaseCaptured',
            { id: item.testCaseId, date: date(item.capturedAt) }, 'Frozen case #{id} · captured {date}'))),
        badge(item.currentOutcome), $('<button type="button" class="btn btn-outline-primary btn-sm">').text(t("execution.viewSnapshotAndAttempts", null, 'View snapshot and attempts'))
          .on('click', function () { openCase(item.runCaseId); })));
    });
    if (run.status === 'IN_PROGRESS') {
      [[t("execution.completeRun", null, 'Complete run'), 'complete'], [t("execution.cancelRun", null, 'Cancel run'), 'cancel']].forEach(function (entry) {
        $('#execution-actions').append($('<button type="button" class="btn btn-outline-secondary">').text(entry[0])
          .on('click', function () { finishRun(run, entry[1]); }));
      });
    }
  }
  async function finishRun(run, action) {
    if (!window.confirm(t(action === 'complete' ? 'execution.completeRunConfirm' : 'execution.cancelRunConfirm',
      { name: run.name }, action === 'complete' ? 'Complete run "{name}"?' : 'Cancel run "{name}"?'))) return;
    const token = generation;
    $('#execution-actions button').prop('disabled', true);
    try { await api.post(path(run.id) + '/' + action, { expectedVersion: run.version }); if (valid(token)) await openDetail(run.id); }
    catch (failure) { if (valid(token)) notice(conflict(failure), true); }
    finally { if (valid(token)) $('#execution-actions button').prop('disabled', false); }
  }
  function snapshot(item) {
    const target = $('#execution-snapshot').empty();
    target.append($('<dl class="asset-fields">').append(
      field(t("execution.titleAtCapture", null, 'Title at capture'), item.snapshotTitle), field(t("execution.priorityAtCapture", null, 'Priority at capture'), label(item.snapshotPriority)),
      field(t("execution.descriptionAtCapture", null, 'Description at capture'), item.snapshotDescription), field(t("execution.preconditionsAtCapture", null, 'Preconditions at capture'), item.snapshotPreconditions),
      field(t("execution.captured", null, 'Captured'), date(item.capturedAt)), field(t("execution.currentOutcome", null, 'Current outcome'), label(item.currentOutcome))));
    target.append($('<h3 class="fs-5 mt-3">').text(t("execution.snapshotSteps", null, 'Snapshot steps')));
    item.steps.forEach(function (step) {
      target.append($('<div class="asset-step">').append($('<strong>').text(t('assets.stepNumber', { number: step.stepOrder }, 'Step {number}')),
        $('<div>').append($('<span class="text-secondary">').text(t("execution.action", null, 'Action: ')), document.createTextNode(step.action)),
        $('<div>').append($('<span class="text-secondary">').text(t("execution.expected", null, 'Expected: ')), document.createTextNode(step.expectedResult))));
    });
  }
  async function openCase(id) {
    if (!detail || view !== 'runs') return;
    const runId = detail.run.id;
    const token = ++generation;
    runCaseId = id; pendingAttempt = null;
    panels('case'); notice(''); attemptError('');
    $('#execution-case-title').text(t('execution.loadingSnapshot', null, 'Loading snapshot…'));
    $('#execution-snapshot, #execution-history').empty();
    $('#execution-history-status').text(t('execution.loadingAttemptHistory', null, 'Loading attempt history…'));
    $('#execution-attempt-form').addClass('d-none');
    try {
      const current = await api.get(path(runId));
      if (!valid(token) || runCaseId !== id) return;
      const item = current.cases.find(function (row) { return row.runCaseId === id; });
      if (!item) throw { message: t("execution.runCaseIsNoLongerAvailable", null, 'Run case is no longer available.') };
      const history = await api.get(path(runId) + '/cases/' + encodeURIComponent(id) + '/attempts');
      if (!valid(token) || runCaseId !== id) return;
      detail = current;
      $('#execution-case-title').text(t('execution.snapshotTitle', { title: item.snapshotTitle }, 'Snapshot · {title}'));
      snapshot(item);
      const target = $('#execution-history').empty();
      $('#execution-history-status').text(history.length ? t('execution.attemptCount', { count: window.I18n.formatNumber(history.length) }, '{count} recorded attempt(s)') : t("execution.noAttemptsYet", null, 'No attempts yet.'));
      history.forEach(function (attempt, index) {
        const card = $('<div class="execution-attempt">').append(
          $('<div class="asset-panel-heading mb-1">').append($('<strong>').text(t(index === history.length - 1 ? 'execution.latestAttempt' : 'execution.attemptNumber',
            { number: attempt.attemptNo }, index === history.length - 1 ? 'Attempt #{number} · latest' : 'Attempt #{number}')), badge(attempt.outcome)),
          $('<dl class="asset-fields mb-0">').append(field(t("execution.executed", null, 'Executed'), date(attempt.executedAt)), field(t("execution.actorID", null, 'Actor ID'), attempt.executedBy),
            field(t("execution.durationMs", null, 'Duration (ms)'), attempt.durationMs), field(t("execution.actualResultComment", null, 'Actual result / comment'), attempt.comment), field(t("execution.failureMessage", null, 'Failure message'), attempt.failureMessage)));
        target.append(card);
        if (attempt.outcome === 'FAIL') card.append($('<button type="button" class="btn btn-outline-danger btn-sm mt-2">')
          .text(t("execution.createDefectFromThisFAIL", null, 'Create defect from this FAIL')).on('click', function () {
            document.dispatchEvent(new CustomEvent('veriqra:failure-evidence', { detail: {
              projectId: projectId, runId: runId, runCaseId: id, attemptId: attempt.id
            } }));
            location.hash = '#defects';
          }));
      });
      $('#execution-attempt-form').toggleClass('d-none', current.run.status !== 'IN_PROGRESS');
      $('#execution-attempt-form')[0].reset();
      $('#execution-failure-wrap').addClass('d-none');
      $('#execution-submit').prop('disabled', false).text(history.length ? t("execution.recordRetestAsNewAttempt", null, 'Record retest as new attempt') : t("execution.recordNewAttempt", null, 'Record new attempt'));
    } catch (failure) { if (valid(token)) { $('#execution-history-status').text(''); notice(failure.message, true); } }
  }

  async function showForm(update) {
    if (!projectId || !active()) return;
    const token = ++generation;
    editing = update;
    panels('form'); notice(''); formError('');
    $('#execution-form')[0].reset();
    $('#execution-save').prop('disabled', true);
    $('#execution-form-title').text(view === 'test-plans' ? (update ? t("execution.editPlan", null, 'Edit plan') : t("execution.createPlan", null, 'Create plan')) : t("execution.createRun", null, 'Create run'));
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
        caseOptions = cases.filter(function (item) { return view === 'test-plans' ? item.status !== 'ARCHIVED' : item.status === 'READY'; });
        caseSelector(caseOptions, []);
        if (view === 'runs') {
          const plans = await api.get(base('test-plans'));
          if (!valid(token)) return;
          plans.filter(function (plan) { return plan.status === 'READY'; }).forEach(function (plan) {
            $('#execution-plan').append($('<option>').val(plan.id).text(planKey(plan) + ' · ' + plan.name));
          });
          if (!$('#execution-plan option').length) $('#execution-plan').append($('<option>').val('').text(t("execution.noREADYPlanAvailable", null, 'No READY plan available')));
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
  document.addEventListener('veriqra:localechange', async function () {
    if (!projectId || !active()) return;
    if (!$('#execution-form').hasClass('d-none')) {
      $('#execution-form-title').text(view === 'test-plans'
        ? t(editing ? 'execution.editPlan' : 'execution.createPlan') : t('execution.createRun'));
      if (caseOptions.length) caseSelector(caseOptions, selectedCases().map(String));
    } else if (!$('#execution-case-panel').hasClass('d-none') && runCaseId != null) {
      const values = $('#execution-attempt-form').find('input,select,textarea').map(function () {
        return { id: this.id, value: this.value };
      }).get();
      const retry = pendingAttempt;
      const id = runCaseId;
      await openCase(id);
      values.forEach(function (entry) { if (entry.id) $('#' + entry.id).val(entry.value); });
      $('#execution-outcome').trigger('change');
      pendingAttempt = retry;
    } else if (detail && !$('#execution-detail-panel').hasClass('d-none')) {
      await openDetail(view === 'test-plans' ? detail.plan.id : detail.run.id);
    } else list();
  });
  let requestedRun = null;
  $(document).on('veriqra:open-run', function (event) {
    const request = event.originalEvent.detail;
    if (projectId && String(request.projectId) === projectId) requestedRun = request;
  });
  $(document).on('veriqra:project', function (event) {
    const next = event.originalEvent.detail.project;
    if (!next || !projectId || String(next.id) !== projectId) requestedRun = null;
  });
  $(document).on('veriqra:view', function (event) {
    if (event.originalEvent.detail.view !== 'runs' || !requestedRun || !projectId) return;
    if (String(requestedRun.projectId) !== projectId) { requestedRun = null; return; }
    const id = requestedRun.runId;
    requestedRun = null;
    openDetail(id);
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
    if (!name) { formError(t("execution.nameIsRequired", null, 'Name is required.')); button.prop('disabled', false); return; }
    let body;
    if (view === 'test-plans') {
      body = { name: name, description: $('#execution-description').val().trim() || null };
      if (editing) { body.status = $('#execution-status').val(); body.expectedVersion = detail.plan.version; }
      else body.testCaseIds = selectedCases();
    } else {
      body = { name: name, environment: $('#execution-environment').val().trim() || null,
        buildVersion: $('#execution-build').val().trim() || null };
      if ($('#execution-origin').val() === 'plan') {
        if (!$('#execution-plan').val()) { formError(t("execution.selectAREADYPlan", null, 'Select a READY plan.')); button.prop('disabled', false); return; }
        body.testPlanId = Number($('#execution-plan').val());
      } else {
        body.testCaseIds = selectedCases();
        if (!body.testCaseIds.length) { formError(t("execution.selectAtLeastOneREADYTestCase", null, 'Select at least one READY test case.')); button.prop('disabled', false); return; }
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
    if (body.durationMs != null && (!Number.isSafeInteger(body.durationMs) || body.durationMs < 0)) { attemptError(t("execution.durationMustBeANonNegativeWholeNumber", null, 'Duration must be a non-negative whole number.')); return; }
    const signature = JSON.stringify(body);
    if (!pendingAttempt || pendingAttempt.signature !== signature) pendingAttempt = { signature: signature, body: Object.assign({}, body, { submissionKey: crypto.randomUUID() }) };
    const button = $('#execution-submit').prop('disabled', true);
    attemptError('');
    try {
      await api.post(path(runId) + '/cases/' + encodeURIComponent(runCaseId) + '/attempts', pendingAttempt.body);
      if (valid(token)) { pendingAttempt = null; await openCase(runCaseId); }
    } catch (failure) { if (valid(token)) attemptError(conflict(failure) + ' ' + t('execution.retryAttemptKey', null, 'Retrying unchanged values reuses this attempt key.')); }
    finally { if (valid(token)) button.prop('disabled', false); }
  });
})(jQuery, window.VeriqraApi);
