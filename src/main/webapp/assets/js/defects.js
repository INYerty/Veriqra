(function ($, api) {
  'use strict';
  const t = window.I18n.t;
  let projectId = null;
  let view = 'dashboard';
  let generation = 0;
  let detail = null;
  let actionMode = null;
  let launch = null;
  const pickerGeneration = { 'defect-create': 0, 'defect-action': 0 };
  const active = function () { return view === 'defects' && !!projectId; };
  const valid = function (token) { return active() && token === generation; };
  const base = function (resource) { return 'projects/' + encodeURIComponent(projectId) + '/' + resource; };
  const defectPath = function (id) { return base('defects') + '/' + encodeURIComponent(id); };
  const runPath = function (id) { return base('runs') + '/' + encodeURIComponent(id); };
  const label = function (value) { return window.I18n.enumLabel(value); };
  const date = function (value) { return window.I18n.formatDateTime(value); };
  const field = function (name, value) {
    return $('<div class="asset-field">').append($('<dt>').text(name),
      $('<dd>').text(value == null || value === '' ? '—' : String(value)));
  };
  const badge = function (value) {
    const colors = { OPEN: 'danger', IN_PROGRESS: 'primary', RESOLVED: 'info', CLOSED: 'success', REOPENED: 'warning', FAIL: 'danger', PASS: 'success', BLOCKED: 'warning', SKIPPED: 'info' };
    return $('<span>').addClass('badge text-bg-' + (colors[value] || 'secondary')).text(label(value));
  };
  function notice(message, error) {
    $('#defect-notice').text(message || '').toggleClass('d-none', !message)
      .toggleClass('alert-danger', !!error).toggleClass('alert-success', !!message && !error);
  }
  function actionStatus(message, error) {
    $('#defect-action-status').text(message || '').toggleClass('d-none', !message)
      .toggleClass('alert-danger', !!error).toggleClass('alert-success', !!message && !error)
      .attr('role', error ? 'alert' : 'status').attr('aria-live', error ? 'assertive' : 'polite');
  }
  function formError(id, message) { $(id).text(message || '').toggleClass('d-none', !message); }
  function conflict(error, action) {
    if (error.status !== 409) return error.message;
    return action === 'close'
      ? t('defects.closeRejected', null, 'Close was rejected. Each linked FAIL needs a later current PASS; the defect must also remain RESOLVED and unchanged. Reload the latest detail before trying again.')
      : t('defects.conflict', null, 'The record changed or this action is not allowed in its current state. Reload the latest detail before trying again.');
  }
  function reset() {
    generation++;
    pickerGeneration['defect-create']++;
    pickerGeneration['defect-action']++;
    detail = null; actionMode = null;
    $('#defect-list, #defect-detail, #defect-evidence, #defect-actions').empty();
    $('#defect-list-status, #defect-evidence-status').text('');
    $('#defect-list-panel, #defect-detail-panel, #defect-create-form, #defect-action-form').addClass('d-none');
    $('#defect-create-submit, #defect-action-submit').prop('disabled', false);
    notice(''); actionStatus(''); formError('#defect-create-error', ''); formError('#defect-action-error', '');
  }
  function panels(which) {
    $('#defect-list-panel').toggleClass('d-none', which !== 'list');
    $('#defect-detail-panel').toggleClass('d-none', which !== 'detail');
    $('#defect-create-form').toggleClass('d-none', which !== 'create');
  }
  async function list() {
    if (!active()) return;
    const token = ++generation;
    detail = null; actionMode = null;
    panels('list'); notice('');
    $('#defect-list').empty(); $('#defect-list-status').text(t("defects.loadingDefects", null, 'Loading defects…'));
    try {
      const rows = await api.get(base('defects'));
      if (!valid(token)) return;
      $('#defect-list-status').text(rows.length ? t('defects.defectCount', { count: window.I18n.formatNumber(rows.length) }, '{count} defect(s)') : t("defects.noDefectsInThisProject", null, 'No defects in this project.'));
      rows.forEach(function (row) {
        const title = 'BUG-' + String(row.keyNo).padStart(3, '0') + ' · ' + row.title;
        $('#defect-list').append($('<div class="asset-list-row">').append(
          $('<div class="asset-list-summary">').append($('<strong>').text(title),
            $('<p class="mb-1 text-secondary">').text(row.description || t("common.noDescription", null, 'No description'))),
          $('<div class="asset-list-meta">').append(badge(row.status),
            $('<span>').text(t('defects.severityPriority', { severity: label(row.severity), priority: label(row.priority) }, 'Severity {severity} · Priority {priority}')),
            $('<span>').text('v' + row.version), $('<small>').text(t('defects.updatedAt', { date: date(row.updatedAt) }, 'Updated {date}'))),
          $('<button type="button" class="btn btn-outline-primary btn-sm">').text(t("defects.viewDefect", null, 'View defect'))
            .on('click', function () { openDetail(row.id); })));
      });
    } catch (error) { if (valid(token)) { $('#defect-list-status').text(''); notice(error.message, true); } }
  }
  function openRun(runId) {
    document.dispatchEvent(new CustomEvent('veriqra:open-run', { detail: { projectId: projectId, runId: runId } }));
    location.hash = '#runs';
  }
  async function enrichEvidence(row) {
    const sourceRun = runPath(row.runId);
    const token = generation;
    try {
      const run = await api.get(sourceRun);
      if (!valid(token)) return { link: row, run: null, runCase: null, history: [] };
      const runCase = run.cases.find(function (item) { return item.runCaseId === row.runCaseId; });
      const history = await api.get(sourceRun + '/cases/' + encodeURIComponent(row.runCaseId) + '/attempts');
      return { link: row, run: run.run, runCase: runCase, history: history };
    } catch (_) { return { link: row, run: null, runCase: null, history: [] }; }
  }
  function evidenceCard(value, defect) {
    const link = value.link;
    const source = value.history.find(function (attempt) { return attempt.id === link.attemptId; });
    const card = $('<div class="execution-attempt">').append(
      $('<div class="asset-panel-heading mb-1">').append(
        $('<strong>').text(t('defects.linkedFailAttempt', { number: link.attemptNo, id: link.attemptId }, 'Linked FAIL attempt #{number} · ID {id}')), badge(link.outcome)),
      $('<dl class="asset-fields mb-2">').append(
        field(t('defects.run', null, 'Run'), value.run ? value.run.name + ' · ID ' + link.runId : t('defects.runNumber', { id: link.runId }, 'Run #{id}')),
        field(t("defects.runCaseSnapshot", null, 'Run Case snapshot'), value.runCase ? value.runCase.snapshotTitle + ' · ID ' + link.runCaseId
          : t('defects.runCaseNumber', { id: link.runCaseId }, 'Run Case #{id}')),
        field(t("defects.executed", null, 'Executed'), source ? date(source.executedAt) : null), field(t("defects.actorID", null, 'Actor ID'), source ? source.executedBy : null),
        field(t("defects.actualResultComment", null, 'Actual result / comment'), source ? source.comment : null),
        field(t("defects.failureMessage", null, 'Failure message'), source ? source.failureMessage : link.failureMessage),
        field(t('defects.linked', null, 'Linked'), date(link.linkedAt))));
    const later = value.history.filter(function (attempt) { return attempt.attemptNo > link.attemptNo; });
    card.append($('<p class="small fw-semibold mb-1">').text(t("defects.laterRetestsOnThisRunCase", null, 'Later retests on this Run Case')));
    if (!later.length) card.append($('<p class="small text-secondary">').text(t("defects.noLaterAttemptRecorded", null, 'No later attempt recorded.')));
    later.forEach(function (attempt) {
      card.append($('<div class="defect-retest">').append(
        $('<span>').text(t('defects.attemptAt', { number: attempt.attemptNo, date: date(attempt.executedAt) }, 'Attempt #{number} · {date} · ')), badge(attempt.outcome),
        $('<span>').text(' ' + (attempt.comment || attempt.failureMessage || t("defects.noComment", null, 'No comment')))));
    });
    card.append($('<button type="button" class="btn btn-outline-primary btn-sm">').text(t("defects.openSourceRun", null, 'Open source Run'))
      .on('click', function () { openRun(link.runId); }));
    if (defect.status !== 'CLOSED') card.append($('<button type="button" class="btn btn-outline-danger btn-sm ms-2">')
      .text(t("defects.removeLinkCorrection", null, 'Remove link (correction)')).on('click', function () { removeEvidence(link, defect); }));
    return card;
  }
  async function openDetail(id, feedback) {
    if (!active()) return;
    const token = ++generation;
    actionMode = null; detail = null; panels('detail'); notice(''); actionStatus('');
    $('#defect-detail-panel button').prop('disabled', false);
    $('#defect-action-form').addClass('d-none');
    $('#defect-detail-title').text(t("defects.loadingDefect", null, 'Loading defect…'));
    $('#defect-detail, #defect-evidence, #defect-actions').empty();
    $('#defect-evidence-status').text(t("defects.loadingLinkedEvidence", null, 'Loading linked evidence…'));
    try {
      const current = await api.get(defectPath(id));
      if (!valid(token)) return;
      detail = current;
      const defect = current.defect;
      $('#defect-detail-title').text('BUG-' + String(defect.keyNo).padStart(3, '0') + ' · ' + defect.title);
      $('#defect-detail').append($('<dl class="asset-fields">').append(
        field(t("common.status", null, 'Status'), label(defect.status)), field(t("common.severity", null, 'Severity'), label(defect.severity)),
        field(t("common.priority", null, 'Priority'), label(defect.priority)), field(t("common.version", null, 'Version'), defect.version),
        field(t("common.description", null, 'Description'), defect.description), field(t('defects.reporterID', null, 'Reporter ID'), defect.reporterId),
        field(t('defects.assigneeID', null, 'Assignee ID'), defect.assigneeId), field(t("defects.resolutionNote", null, 'Resolution note'), defect.resolutionNote),
        field(t("common.created", null, 'Created'), date(defect.createdAt)), field(t("common.updated", null, 'Updated'), date(defect.updatedAt))));
      renderActions(defect);
      if (feedback) actionStatus(feedback, false);
      const evidence = await Promise.all(current.evidence.map(enrichEvidence));
      if (!valid(token) || detail !== current) return;
      $('#defect-evidence-status').text(evidence.length ? t('defects.evidenceCount', { count: window.I18n.formatNumber(evidence.length) }, '{count} linked FAIL attempt(s)') : t("defects.noLinkedFailureEvidence", null, 'No linked failure evidence.'));
      evidence.forEach(function (row) { $('#defect-evidence').append(evidenceCard(row, defect)); });
    } catch (error) { if (valid(token)) { $('#defect-evidence-status').text(''); notice(error.message, true); } }
  }
  function renderActions(defect) {
    const actions = $('#defect-actions').empty();
    if (['OPEN', 'REOPENED'].includes(defect.status)) actions.append(actionButton(t("defects.startWork", null, 'Start work'), function () { transition('start'); }));
    if (defect.status === 'IN_PROGRESS') actions.append(actionButton(t("defects.resolve", null, 'Resolve'), function () { showAction('resolve'); }));
    if (defect.status === 'RESOLVED') actions.append(actionButton(t("common.close", null, 'Close'), function () { transition('close'); }));
    if (['RESOLVED', 'CLOSED'].includes(defect.status)) actions.append(actionButton(t("defects.reopenWithNewFAIL", null, 'Reopen with new FAIL'), function () { showAction('reopen'); }));
    if (defect.status !== 'CLOSED') actions.append(actionButton(t("defects.addFailureEvidence", null, 'Add failure evidence'), function () { showAction('add'); }));
  }
  function actionButton(text, callback) { return $('<button type="button" class="btn btn-outline-primary">').text(text).on('click', callback); }
  async function transition(mode) {
    const defect = detail && detail.defect;
    if (!defect || !window.confirm(t(mode === 'close' ? 'defects.closeConfirm' : 'defects.startConfirm',
      { key: 'BUG-' + defect.keyNo }, mode === 'close' ? 'Close {key}?' : 'Start {key}?'))) return;
    const token = generation;
    $('#defect-detail-panel button').prop('disabled', true);
    try {
      await api.post(defectPath(defect.id) + '/' + mode, { expectedVersion: defect.version });
      if (valid(token)) await openDetail(defect.id, mode === 'close' ? t("defects.defectClosed", null, 'Defect closed.') : t("defects.workStarted", null, 'Work started.'));
    } catch (error) { if (valid(token)) actionStatus(conflict(error, mode), true); }
    finally { if (valid(token)) $('#defect-detail-panel button').prop('disabled', false); }
  }
  async function removeEvidence(link, defect) {
    if (!window.confirm(t('defects.removeEvidenceConfirm', { number: link.attemptNo },
      'Remove the evidence link for FAIL attempt #{number}? The Attempt itself remains in Run history.'))) return;
    const token = generation;
    $('#defect-detail-panel button').prop('disabled', true);
    try {
      await api.post(defectPath(defect.id) + '/evidence/' + link.attemptId + '/remove', {});
      if (valid(token)) await openDetail(defect.id);
    } catch (error) { if (valid(token)) notice(conflict(error), true); }
    finally { if (valid(token)) $('#defect-detail-panel button').prop('disabled', false); }
  }
  function pickerValid(prefix, token, pickerToken) {
    return valid(token) && pickerGeneration[prefix] === pickerToken;
  }
  function option(value, text) { return $('<option>').val(String(value)).text(text); }
  async function loadRuns(prefix, prefill) {
    const token = generation, pickerToken = ++pickerGeneration[prefix];
    const run = $('#' + prefix + '-run').empty().append(option('', t('defects.loadingRuns', null, 'Loading runs…')));
    $('#' + prefix + '-case, #' + prefix + '-attempt').empty();
    try {
      const rows = await api.get(base('runs'));
      if (!pickerValid(prefix, token, pickerToken)) return;
      run.empty().append(option('', t("defects.selectARun", null, 'Select a Run')));
      rows.forEach(function (item) { run.append(option(item.id, item.name + ' · #' + item.id)); });
      if (prefill && rows.some(function (item) { return item.id === prefill.runId; })) {
        run.val(String(prefill.runId)); await loadCases(prefix, prefill);
      }
    } catch (error) { if (pickerValid(prefix, token, pickerToken)) formError(prefix === 'defect-create' ? '#defect-create-error' : '#defect-action-error', error.message); }
  }
  async function loadCases(prefix, prefill) {
    const token = generation, pickerToken = ++pickerGeneration[prefix];
    const runId = $('#' + prefix + '-run').val();
    const select = $('#' + prefix + '-case').empty().append(option('', runId ? t('defects.loadingRunCases', null, 'Loading Run Cases…') : t("defects.selectARunFirst", null, 'Select a Run first')));
    $('#' + prefix + '-attempt').empty();
    if (!runId) return;
    try {
      const result = await api.get(runPath(runId));
      if (!pickerValid(prefix, token, pickerToken)) return;
      select.empty().append(option('', t("defects.selectARunCase", null, 'Select a Run Case')));
      result.cases.forEach(function (item) { select.append(option(item.runCaseId, item.snapshotTitle + ' · #' + item.runCaseId)); });
      if (prefill && result.cases.some(function (item) { return item.runCaseId === prefill.runCaseId; })) {
        select.val(String(prefill.runCaseId)); await loadAttempts(prefix, prefill);
      }
    } catch (error) { if (pickerValid(prefix, token, pickerToken)) formError(prefix === 'defect-create' ? '#defect-create-error' : '#defect-action-error', error.message); }
  }
  async function loadAttempts(prefix, prefill) {
    const token = generation, pickerToken = ++pickerGeneration[prefix];
    const runId = $('#' + prefix + '-run').val(), caseId = $('#' + prefix + '-case').val();
    const select = $('#' + prefix + '-attempt').empty().append(option('', caseId ? t('defects.loadingFailAttempts', null, 'Loading FAIL attempts…') : t("defects.selectARunCaseFirst", null, 'Select a Run Case first')));
    if (!runId || !caseId) return;
    try {
      const history = await api.get(runPath(runId) + '/cases/' + encodeURIComponent(caseId) + '/attempts');
      if (!pickerValid(prefix, token, pickerToken)) return;
      select.empty().append(option('', t("defects.selectAFAILAttempt", null, 'Select a FAIL attempt')));
      history.filter(function (item) { return item.outcome === 'FAIL' &&
        !(prefix === 'defect-action' && actionMode === 'reopen' && detail &&
          detail.evidence.some(function (link) { return link.attemptId === item.id; })); }).forEach(function (item) {
        select.append(option(item.id, 'FAIL #' + item.attemptNo + ' · ' + date(item.executedAt) + ' · ID ' + item.id));
      });
      if (prefill && history.some(function (item) { return item.id === prefill.attemptId && item.outcome === 'FAIL'; })) select.val(String(prefill.attemptId));
    } catch (error) { if (pickerValid(prefix, token, pickerToken)) formError(prefix === 'defect-create' ? '#defect-create-error' : '#defect-action-error', error.message); }
  }
  function selectedFailure(prefix) {
    const id = Number($('#' + prefix + '-attempt').val());
    return Number.isSafeInteger(id) && id > 0 ? id : null;
  }
  function assignee(selector, required) {
    const raw = $(selector).val().trim();
    if (!raw && !required) return null;
    const id = Number(raw);
    return Number.isSafeInteger(id) && id > 0 ? id : null;
  }
  function showCreate(prefill) {
    if (!active()) return;
    ++generation; detail = null; actionMode = null;
    panels('create'); notice(''); formError('#defect-create-error', '');
    $('#defect-create-form')[0].reset();
    $('#defect-create-form input, #defect-create-form select, #defect-create-form textarea, #defect-create-form button').prop('disabled', false);
    loadRuns('defect-create', prefill);
  }
  function showAction(mode) {
    if (!detail) return;
    actionMode = mode;
    $('#defect-action-form')[0].reset(); formError('#defect-action-error', '');
    $('#defect-action-form input, #defect-action-form select, #defect-action-form textarea, #defect-action-submit').prop('disabled', false);
    $('#defect-action-form').removeClass('d-none');
    $('#defect-action-title').text({ resolve: t("defects.resolveDefect", null, 'Resolve defect'), reopen: t("defects.reopenWithANewFAIL", null, 'Reopen with a new FAIL'), add: t("defects.addFailureEvidence", null, 'Add failure evidence') }[mode]);
    $('#defect-action-help').text(mode === 'reopen' ? t('defects.reopenHelp', null, 'Choose a new, unlinked FAIL attempt and an active Developer/Admin assignee.')
      : mode === 'resolve' ? t('defects.resolveHelp', null, 'A resolution note is required. Closing later still requires a current PASS for every linked FAIL.')
        : t('defects.addEvidenceHelp', null, 'Only an existing FAIL attempt can be linked; removing a mistaken link never removes its history.'));
    $('#defect-resolution-wrap').toggleClass('d-none', mode !== 'resolve');
    $('#defect-action-assignee-wrap').toggleClass('d-none', mode !== 'reopen');
    $('#defect-action-evidence').toggleClass('d-none', mode === 'resolve');
    $('#defect-action-assignee').val(mode === 'reopen' && detail.defect.assigneeId ? detail.defect.assigneeId : '');
    $('#defect-action-submit').prop('disabled', false);
    if (mode !== 'resolve') loadRuns('defect-action');
  }
  $(document).on('veriqra:failure-evidence', function (event) {
    const value = event.originalEvent.detail;
    if (projectId && String(value.projectId) === projectId) launch = value;
  });
  $(document).on('veriqra:project', function (event) {
    const next = event.originalEvent.detail.project;
    if (!next || (projectId && String(next.id) !== projectId)) launch = null;
    reset(); projectId = next ? String(next.id) : null;
    if (active()) list();
  });
  $(document).on('veriqra:view', function (event) {
    const next = event.originalEvent.detail.view;
    if (next !== view) { reset(); view = next; }
    if (!active()) return;
    if (launch && String(launch.projectId) === projectId) { const prefill = launch; launch = null; showCreate(prefill); }
    else list();
  });
  document.addEventListener('veriqra:localechange', function () {
    if (!active()) return;
    for (const prefix of ['defect-create', 'defect-action']) {
      const run = $('#' + prefix + '-run'), runCase = $('#' + prefix + '-case'), attempt = $('#' + prefix + '-attempt');
      if (run.find('option[value=""]').length) run.find('option[value=""]').text(t('defects.selectARun', null, 'Select a Run'));
      if (runCase.find('option[value=""]').length) runCase.find('option[value=""]').text(t(run.val() ? 'defects.selectARunCase' : 'defects.selectARunFirst'));
      if (attempt.find('option[value=""]').length) attempt.find('option[value=""]').text(t(runCase.val() ? 'defects.selectAFAILAttempt' : 'defects.selectARunCaseFirst'));
    }
    if (!$('#defect-create-form').hasClass('d-none')) return;
    if (detail && !$('#defect-detail-panel').hasClass('d-none')) {
      // Close the unsubmitted action form before refreshing translated detail and actions.
      $('#defect-action-form').addClass('d-none');
      openDetail(detail.defect.id);
    }
    else list();
  });
  $('#defect-new').on('click', function () { showCreate(null); });
  $('#defect-back, #defect-create-cancel').on('click', list);
  $('#defect-action-cancel').on('click', function () { actionMode = null; $('#defect-action-form').addClass('d-none'); });
  ['defect-create', 'defect-action'].forEach(function (prefix) {
    $('#' + prefix + '-run').on('change', function () { loadCases(prefix); });
    $('#' + prefix + '-case').on('change', function () { loadAttempts(prefix); });
  });
  $('#defect-create-form').on('submit', async function (event) {
    event.preventDefault();
    if ($('#defect-create-submit').prop('disabled') || !this.reportValidity()) return;
    const failureAttemptId = selectedFailure('defect-create');
    if (!failureAttemptId) { formError('#defect-create-error', t("defects.selectAnExistingFAILAttempt", null, 'Select an existing FAIL attempt.')); return; }
    const rawAssignee = $('#defect-create-assignee').val().trim(), assigneeId = assignee('#defect-create-assignee', false);
    if (rawAssignee && !assigneeId) { formError('#defect-create-error', t('defects.invalidAssignee', null, 'Enter a valid assignee user ID.')); return; }
    const token = generation;
    const body = { failureAttemptId: failureAttemptId, title: $('#defect-create-title').val().trim(),
      description: $('#defect-create-description').val().trim() || null, severity: $('#defect-create-severity').val(),
      priority: $('#defect-create-priority').val(), assigneeId: assigneeId };
    $('#defect-create-form input, #defect-create-form select, #defect-create-form textarea, #defect-create-form button').prop('disabled', true);
    formError('#defect-create-error', '');
    try {
      const created = await api.post(base('defects'), body);
      if (valid(token)) await openDetail(created.id);
    } catch (error) { if (valid(token)) formError('#defect-create-error', conflict(error)); }
    finally { if (valid(token)) $('#defect-create-form input, #defect-create-form select, #defect-create-form textarea, #defect-create-form button').prop('disabled', false); }
  });
  $('#defect-action-form').on('submit', async function (event) {
    event.preventDefault();
    if ($('#defect-action-submit').prop('disabled') || !detail) return;
    const mode = actionMode, defect = detail.defect, token = generation;
    let body;
    if (mode === 'resolve') {
      const resolutionNote = $('#defect-resolution').val().trim();
      if (!resolutionNote) { formError('#defect-action-error', t("defects.enterAResolutionNote", null, 'Enter a resolution note.')); return; }
      body = { expectedVersion: defect.version, resolutionNote: resolutionNote };
    } else if (mode === 'reopen' || mode === 'add') {
      const failureAttemptId = selectedFailure('defect-action');
      if (!failureAttemptId) { formError('#defect-action-error', t("defects.selectAFAILAttemptMessage", null, 'Select a FAIL attempt.')); return; }
      body = mode === 'reopen' ? { expectedVersion: defect.version, failureAttemptId: failureAttemptId,
        assigneeId: assignee('#defect-action-assignee', true) } : { failureAttemptId: failureAttemptId };
      if (mode === 'reopen' && !body.assigneeId) { formError('#defect-action-error', t('defects.invalidActiveAssignee', null, 'Enter a valid active Developer/Admin assignee user ID.')); return; }
    } else return;
    $('#defect-detail-panel button, #defect-action-form select, #defect-action-form input, #defect-action-form textarea')
      .prop('disabled', true);
    formError('#defect-action-error', '');
    try {
      await api.post(defectPath(defect.id) + (mode === 'add' ? '/evidence' : '/' + mode), body);
      if (valid(token)) await openDetail(defect.id, mode === 'resolve' ? t("defects.defectResolved", null, 'Defect resolved.') : null);
    } catch (error) { if (valid(token)) formError('#defect-action-error', conflict(error, mode)); }
    finally { if (valid(token)) $('#defect-detail-panel button, #defect-action-form select, #defect-action-form input, #defect-action-form textarea')
      .prop('disabled', false); }
  });
})(jQuery, window.VeriqraApi);
