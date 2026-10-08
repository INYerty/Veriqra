(function ($, api) {
  'use strict';
  const t = window.I18n.t;
  let projectId = null;
  let view = 'dashboard';
  let generation = 0;
  let detail = null;
  let actionMode = null;
  let launch = null;
  let createMode = 'create';
  let routeContext = {};
  let mutating = false;
  let linkableDefects = [];
  let evidenceValues = null;
  let evidenceGeneration = 0;
  let evidenceLoading = false;
  const pickerData = { 'defect-create': {}, 'defect-action': {} };
  const pickerGeneration = { 'defect-create': 0, 'defect-action': 0 };
  const pickerFailures = { 'defect-create': {}, 'defect-action': {} };
  const active = function () { return view === 'defects' && !!projectId; };
  const valid = function (token) { return active() && token === generation; };
  const base = function (resource) { return 'projects/' + encodeURIComponent(projectId) + '/' + resource; };
  const defectPath = function (id) { return base('defects') + '/' + encodeURIComponent(id); };
  const runPath = function (id) { return base('runs') + '/' + encodeURIComponent(id); };
  const label = function (value) { return window.I18n.enumLabel(value); };
  const date = function (value) { return window.I18n.formatDateTime(value); };
  const sameId = function (left, right) { return left != null && right != null && String(left) === String(right); };
  const navigation = function () { return window.VeriqraQaNavigation; };
  function selectContext(context) {
    routeContext = Object.assign({ projectId: projectId }, context);
    if (routeContext.attemptId) routeContext.failureAttemptId = String(routeContext.attemptId);
    if (navigation()) navigation().select('defects', routeContext);
  }
  function routeLink(text, next, context) {
    const nav = navigation();
    return $('<a class="btn btn-outline-primary btn-sm">').text(text)
      .attr('href', nav ? nav.href(next, Object.assign({ projectId: projectId }, context)) : '#' + next)
      .on('click', function (event) {
        if (!nav || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
        event.preventDefault(); nav.open(next, Object.assign({ projectId: projectId }, context));
      });
  }
  function mutationState(value) {
    mutating = value;
    $('#defect-detail-panel button, #defect-action-form select, #defect-action-form input, #defect-action-form textarea').prop('disabled', value);
    $('#defect-evidence-retry').prop('disabled', value || evidenceLoading);
    if (!value) updatePickerSubmit('defect-action');
  }
  function failureContext(prefix) {
    return { runId: String($('#' + prefix + '-run').val() || ''), runCaseId: String($('#' + prefix + '-case').val() || ''),
      attemptId: String($('#' + prefix + '-attempt').val() || '') };
  }
  function announceLinked(defectId, context, sourceProjectId) {
    document.dispatchEvent(new CustomEvent('veriqra:defect-linked', { detail: Object.assign({ projectId: sourceProjectId, defectId: String(defectId) }, context) }));
  }
  const field = function (name, value) {
    return $('<div class="asset-field">').append($('<dt>').text(name),
      $('<dd>').text(value == null || value === '' ? '—' : String(value)));
  };
  const badge = function (value) {
    const colors = { OPEN: 'danger', IN_PROGRESS: 'primary', RESOLVED: 'info', CLOSED: 'success', REOPENED: 'warning', FAIL: 'danger', PASS: 'success', BLOCKED: 'warning', SKIPPED: 'info' };
    return $('<span>').addClass('badge text-bg-' + (colors[value] || 'secondary')).attr('data-vq-state', value).text(label(value));
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
  function focusHeading(selector) { $(selector).attr('tabindex', '-1').trigger('focus'); }
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
    detail = null; actionMode = null; mutating = false; evidenceValues = null; evidenceLoading = false; linkableDefects = [];
    pickerData['defect-create'] = {}; pickerData['defect-action'] = {};
    clearPickerFailures('defect-create'); clearPickerFailures('defect-action');
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
    detail = null; actionMode = null; mutating = false;
    panels('list'); notice('');
    $('#defect-list').empty(); $('#defect-list-status').text(t("defects.loadingDefects", null, 'Loading defects…'));
    focusHeading('#defect-list-panel h2');
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
          routeLink(t("defects.viewDefect", null, 'View defect'), 'defects', { defectId: String(row.id) })));
      });
    } catch (error) { if (valid(token)) {
      $('#defect-list-status').text(t('common.unavailable', null, 'Unavailable')); notice(error.message, true);
      $('#defect-list').append($('<button type="button" class="btn btn-outline-secondary btn-sm">')
        .text(t('common.retry', null, 'Retry')).on('click', function () { if (valid(token)) return list(); }));
    } }
  }
  async function loadEvidence(current, token) {
    const evidenceToken = ++evidenceGeneration;
    evidenceLoading = true;
    const requests = new Map();
    function shared(path) {
      if (!requests.has(path)) requests.set(path, Promise.resolve(api.get(path)));
      return requests.get(path);
    }
    $('#defect-evidence-status').text(t('defects.loadingLinkedEvidence', null, 'Loading linked evidence…'));
    $('#defect-evidence-retry').prop('disabled', true);
    const values = await Promise.all(current.evidence.map(async function (row) {
      const sourceRun = runPath(row.runId);
      const results = await Promise.allSettled([shared(sourceRun), shared(sourceRun + '/cases/' + encodeURIComponent(row.runCaseId) + '/attempts')]);
      const run = results[0].status === 'fulfilled' ? results[0].value : null;
      const history = results[1].status === 'fulfilled' ? results[1].value : null;
      const runCase = run && run.cases.find(function (item) { return sameId(item.runCaseId, row.runCaseId); });
      return { link: row, run: run && run.run, runCase: runCase, history: history,
        unavailable: !runCase || !history || !history.some(function (attempt) { return sameId(attempt.id, row.attemptId); }) };
    }));
    if (!valid(token) || detail !== current || evidenceToken !== evidenceGeneration) return;
    evidenceValues = values;
    evidenceLoading = false;
    renderEvidence(current);
  }
  function renderEvidence(current) {
    if (!evidenceValues) { $('#defect-evidence-status').text(t('defects.loadingLinkedEvidence', null, 'Loading linked evidence…')); return; }
    $('#defect-evidence').empty();
    $('#defect-evidence-status').text(evidenceValues.length ? t('defects.evidenceCount', { count: window.I18n.formatNumber(evidenceValues.length) }, '{count} linked FAIL attempt(s)') : t('defects.noLinkedFailureEvidence', null, 'No linked failure evidence.'));
    evidenceValues.forEach(function (value) { $('#defect-evidence').append(evidenceCard(value, current.defect)); });
    if (evidenceValues.some(function (value) { return value.unavailable; })) $('#defect-evidence').append(
      $('<button type="button" id="defect-evidence-retry" class="btn btn-outline-primary btn-sm">').text(t('defects.retryEvidence', null, 'Retry evidence'))
        .prop('disabled', mutating || evidenceLoading).on('click', function () { if (!mutating && !evidenceLoading) return loadEvidence(current, generation); }));
  }
  function evidenceCard(value, defect) {
    const link = value.link;
    const source = value.history && value.history.find(function (attempt) { return sameId(attempt.id, link.attemptId); });
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
    const later = (value.history || []).filter(function (attempt) { return attempt.attemptNo > link.attemptNo; });
    card.append($('<p class="small fw-semibold mb-1">').text(t("defects.laterRetestsOnThisRunCase", null, 'Later retests on this Run Case')));
    if (value.unavailable) card.append($('<p class="small text-danger" role="status">').text(t('defects.evidenceUnavailable', null, 'Run or attempt history could not be loaded. Retest status is unavailable.')));
    else if (!later.length) card.append($('<p class="small text-secondary">').text(t("defects.noLaterAttemptRecorded", null, 'No later attempt recorded.')));
    later.forEach(function (attempt) {
      card.append($('<div class="defect-retest">').append(
        $('<span>').text(t('defects.attemptAt', { number: attempt.attemptNo, date: date(attempt.executedAt) }, 'Attempt #{number} · {date} · ')), badge(attempt.outcome),
        $('<span>').text(' ' + (attempt.comment || attempt.failureMessage || t("defects.noComment", null, 'No comment')))));
    });
    const context = { runId: String(link.runId), runCaseId: String(link.runCaseId), attemptId: String(link.attemptId), defectId: String(defect.id) };
    card.append(routeLink(t('defects.openSourceAttempt', null, 'Open source attempt'), 'runs', context));
    if (value.runCase && value.runCase.testCaseId != null) card.append(routeLink(t('defects.openOriginalCase', null, 'Open original Test Case'), 'test-cases', Object.assign({}, context, { testCaseId: String(value.runCase.testCaseId) })).addClass('ms-2'));
    if (defect.status !== 'CLOSED') card.append($('<button type="button" class="btn btn-outline-danger btn-sm ms-2">')
      .text(t("defects.removeLinkCorrection", null, 'Remove link (correction)')).prop('disabled', mutating).on('click', function () { return removeEvidence(link, defect); }));
    return card;
  }
  async function openDetail(id, feedback) {
    if (!active()) return;
    const token = ++generation;
    actionMode = null; detail = null; mutating = false; evidenceValues = null; evidenceLoading = false; panels('detail'); notice(''); actionStatus('');
    selectContext(Object.assign({}, routeContext, { defectId: String(id), action: undefined }));
    $('#defect-detail-panel button').prop('disabled', false);
    $('#defect-action-form').addClass('d-none');
    $('#defect-detail-title').text(t("defects.loadingDefect", null, 'Loading defect…'));
    focusHeading('#defect-detail-title');
    $('#defect-detail, #defect-evidence, #defect-actions').empty();
    $('#defect-evidence-status').text(t("defects.loadingLinkedEvidence", null, 'Loading linked evidence…'));
    try {
      const current = await api.get(defectPath(id));
      if (!valid(token)) return;
      detail = current;
      const defect = current.defect;
      renderDefect(defect);
      renderActions(defect);
      if (feedback) actionStatus(feedback, false);
      await loadEvidence(current, token);
    } catch (error) { if (valid(token)) {
      $('#defect-detail-title').text(t('common.unavailable', null, 'Unavailable'));
      $('#defect-evidence-status').text(''); notice(error.message, true);
      $('#defect-actions').append($('<button type="button" class="btn btn-outline-secondary btn-sm">')
        .text(t('common.retry', null, 'Retry')).on('click', function () { if (valid(token) && !mutating) return openDetail(id, feedback); }));
    } }
  }
  function renderDefect(defect) {
    $('#defect-detail-title').text('BUG-' + String(defect.keyNo).padStart(3, '0') + ' · ' + defect.title);
    $('#defect-detail').empty().append($('<dl class="asset-fields">').append(
        field(t("common.status", null, 'Status'), label(defect.status)), field(t("common.severity", null, 'Severity'), label(defect.severity)),
        field(t("common.priority", null, 'Priority'), label(defect.priority)), field(t("common.version", null, 'Version'), defect.version),
        field(t("common.description", null, 'Description'), defect.description), field(t('defects.reporterID', null, 'Reporter ID'), defect.reporterId),
        field(t('defects.assigneeID', null, 'Assignee ID'), defect.assigneeId), field(t("defects.resolutionNote", null, 'Resolution note'), defect.resolutionNote),
        field(t("common.created", null, 'Created'), date(defect.createdAt)), field(t("common.updated", null, 'Updated'), date(defect.updatedAt))));
  }
  function renderActions(defect) {
    const actions = $('#defect-actions').empty();
    if (['OPEN', 'REOPENED'].includes(defect.status)) actions.append(actionButton(t("defects.startWork", null, 'Start work'), function () { return transition('start'); }));
    if (defect.status === 'IN_PROGRESS') actions.append(actionButton(t("defects.resolve", null, 'Resolve'), function () { showAction('resolve'); }));
    if (defect.status === 'RESOLVED') actions.append(actionButton(t("common.close", null, 'Close'), function () { return transition('close'); }));
    if (['RESOLVED', 'CLOSED'].includes(defect.status)) actions.append(actionButton(t("defects.reopenWithNewFAIL", null, 'Reopen with new FAIL'), function () { showAction('reopen'); }));
    if (defect.status !== 'CLOSED') actions.append(actionButton(t("defects.addFailureEvidence", null, 'Add failure evidence'), function () { showAction('add'); }));
  }
  function actionButton(text, callback) { return $('<button type="button" class="btn btn-outline-primary">').text(text).prop('disabled', mutating).on('click', function () { if (!mutating) return callback(); }); }
  async function transition(mode) {
    const defect = detail && detail.defect;
    if (mutating || !defect || !window.confirm(t(mode === 'close' ? 'defects.closeConfirm' : 'defects.startConfirm',
      { key: 'BUG-' + defect.keyNo }, mode === 'close' ? 'Close {key}?' : 'Start {key}?'))) return;
    const token = generation;
    mutationState(true);
    try {
      await api.post(defectPath(defect.id) + '/' + mode, { expectedVersion: defect.version });
      if (valid(token)) await openDetail(defect.id, mode === 'close' ? t("defects.defectClosed", null, 'Defect closed.') : t("defects.workStarted", null, 'Work started.'));
    } catch (error) { if (valid(token)) actionStatus(conflict(error, mode), true); }
    finally { if (valid(token)) mutationState(false); }
  }
  async function removeEvidence(link, defect) {
    if (mutating || !detail || !sameId(detail.defect.id, defect.id) || !window.confirm(t('defects.removeEvidenceConfirm', { number: link.attemptNo },
      'Remove the evidence link for FAIL attempt #{number}? The Attempt itself remains in Run history.'))) return;
    const token = generation;
    const removed = { projectId: projectId, defectId: String(defect.id), attemptId: String(link.attemptId), runId: String(link.runId), runCaseId: String(link.runCaseId) };
    mutationState(true);
    try {
      await api.post(defectPath(defect.id) + '/evidence/' + link.attemptId + '/remove', {});
      document.dispatchEvent(new CustomEvent('veriqra:defect-unlinked', { detail: removed }));
      if (valid(token)) await openDetail(defect.id);
    } catch (error) { if (valid(token)) notice(conflict(error), true); }
    finally { if (valid(token)) mutationState(false); }
  }
  function pickerValid(prefix, token, pickerToken) {
    return valid(token) && pickerGeneration[prefix] === pickerToken;
  }
  function option(value, text) { return $('<option>').val(String(value)).text(text); }
  function pickerError(prefix, message) { formError(prefix === 'defect-create' ? '#defect-create-error' : '#defect-action-error', message); }
  function clearPickerFailures(prefix, fields) {
    (fields || ['run', 'case', 'attempt', 'target']).forEach(function (field) {
      $('#' + prefix + '-' + field + '-retry').remove(); delete pickerFailures[prefix][field];
    });
  }
  function pickerReadFailed(prefix, field, error, retry, current) {
    if (!current()) return;
    pickerFailures[prefix][field] = error;
    const select = $('#' + prefix + '-' + field);
    select.empty().append(option('', t('common.unavailable', null, 'Unavailable'))).prop('disabled', true);
    pickerError(prefix, error.message);
    select.after($('<button type="button" class="btn btn-outline-secondary btn-sm mt-2">')
      .attr('id', prefix + '-' + field + '-retry').text(t('common.retry', null, 'Retry')).on('click', function () {
        if (!current() || mutating) return;
        pickerError(prefix, ''); return retry();
      }));
    updatePickerSubmit(prefix);
  }
  function sourceUnavailable(prefix) { pickerError(prefix, t('defects.sourceUnavailable', null, 'The source Run, Run Case, or FAIL attempt is no longer available. Select existing evidence.')); }
  function updatePickerSubmit(prefix) {
    const ready = !!selectedFailure(prefix);
    if (prefix === 'defect-create') $('#defect-create-submit').prop('disabled', mutating || !ready || (createMode === 'link' && !linkableDefects.some(function (item) { return sameId(item.id, $('#defect-create-target').val()); })));
    else $('#defect-action-submit').prop('disabled', mutating || (actionMode !== 'resolve' && !ready));
  }
  function renderCreateContext(sync) {
    const context = failureContext('defect-create');
    if (sync !== false) selectContext(Object.assign({}, routeContext, context, { action: createMode, failureAttemptId: context.attemptId || undefined }));
    const target = $('#defect-create-context').empty();
    if (context.runId && context.runCaseId && context.attemptId) target.append(routeLink(t('defects.openSourceAttempt', null, 'Open source attempt'), 'runs', context));
  }
  async function loadRuns(prefix, prefill) {
    const token = generation, pickerToken = ++pickerGeneration[prefix];
    clearPickerFailures(prefix, ['run', 'case', 'attempt']);
    pickerData[prefix] = {};
    const run = $('#' + prefix + '-run').empty().append(option('', t('defects.loadingRuns', null, 'Loading runs…'))).prop('disabled', true);
    $('#' + prefix + '-case, #' + prefix + '-attempt').empty().prop('disabled', true);
    updatePickerSubmit(prefix);
    try {
      const rows = await api.get(base('runs'));
      if (!pickerValid(prefix, token, pickerToken)) return;
      pickerData[prefix].runs = rows;
      run.prop('disabled', !rows.length);
      run.empty().append(option('', rows.length ? t("defects.selectARun", null, 'Select a Run') : t('defects.noRuns', null, 'No Runs in this project.')));
      rows.forEach(function (item) { run.append(option(item.id, item.name + ' · #' + item.id)); });
      if (prefill && rows.some(function (item) { return sameId(item.id, prefill.runId); })) {
        run.val(String(prefill.runId)); await loadCases(prefix, prefill);
      } else if (prefill && prefill.runId) sourceUnavailable(prefix);
    } catch (error) { pickerReadFailed(prefix, 'run', error, function () { return loadRuns(prefix, prefill); }, function () { return pickerValid(prefix, token, pickerToken); }); }
  }
  async function loadCases(prefix, prefill) {
    const token = generation, pickerToken = ++pickerGeneration[prefix];
    clearPickerFailures(prefix, ['case', 'attempt']);
    const runId = $('#' + prefix + '-run').val();
    pickerData[prefix].cases = []; pickerData[prefix].attempts = [];
    const select = $('#' + prefix + '-case').empty().append(option('', runId ? t('defects.loadingRunCases', null, 'Loading Run Cases…') : t("defects.selectARunFirst", null, 'Select a Run first'))).prop('disabled', true);
    $('#' + prefix + '-attempt').empty().prop('disabled', true);
    updatePickerSubmit(prefix);
    if (!runId) return;
    try {
      const result = await api.get(runPath(runId));
      if (!pickerValid(prefix, token, pickerToken)) return;
      pickerData[prefix].cases = result.cases;
      select.prop('disabled', !result.cases.length);
      select.empty().append(option('', result.cases.length ? t("defects.selectARunCase", null, 'Select a Run Case') : t('defects.noRunCases', null, 'No Run Cases in this Run.')));
      result.cases.forEach(function (item) { select.append(option(item.runCaseId, item.snapshotTitle + ' · #' + item.runCaseId)); });
      if (prefill && result.cases.some(function (item) { return sameId(item.runCaseId, prefill.runCaseId); })) {
        select.val(String(prefill.runCaseId)); await loadAttempts(prefix, prefill);
      } else if (prefill && prefill.runCaseId) sourceUnavailable(prefix);
    } catch (error) { pickerReadFailed(prefix, 'case', error, function () { return loadCases(prefix, prefill); }, function () { return pickerValid(prefix, token, pickerToken); }); }
  }
  async function loadAttempts(prefix, prefill) {
    const token = generation, pickerToken = ++pickerGeneration[prefix];
    clearPickerFailures(prefix, ['attempt']);
    const runId = $('#' + prefix + '-run').val(), caseId = $('#' + prefix + '-case').val();
    pickerData[prefix].attempts = [];
    const select = $('#' + prefix + '-attempt').empty().append(option('', caseId ? t('defects.loadingFailAttempts', null, 'Loading FAIL attempts…') : t("defects.selectARunCaseFirst", null, 'Select a Run Case first'))).prop('disabled', true);
    updatePickerSubmit(prefix);
    if (!runId || !caseId) return;
    try {
      const history = await api.get(runPath(runId) + '/cases/' + encodeURIComponent(caseId) + '/attempts');
      if (!pickerValid(prefix, token, pickerToken)) return;
      const eligible = history.filter(function (item) { return item.outcome === 'FAIL' &&
        !(prefix === 'defect-action' && actionMode === 'reopen' && detail &&
          detail.evidence.some(function (link) { return sameId(link.attemptId, item.id); })); });
      pickerData[prefix].attempts = eligible;
      pickerData[prefix].runId = String(runId); pickerData[prefix].caseId = String(caseId);
      select.empty().append(option('', eligible.length ? t("defects.selectAFAILAttempt", null, 'Select a FAIL attempt') : t('defects.noFailAttempts', null, 'No eligible FAIL attempts on this Run Case.'))).prop('disabled', !eligible.length);
      eligible.forEach(function (item) {
        select.append(option(item.id, 'FAIL #' + item.attemptNo + ' · ' + date(item.executedAt) + ' · ID ' + item.id));
      });
      if (prefill && eligible.some(function (item) { return sameId(item.id, prefill.attemptId || prefill.failureAttemptId); })) select.val(String(prefill.attemptId || prefill.failureAttemptId));
      else if (prefill && (prefill.attemptId || prefill.failureAttemptId)) sourceUnavailable(prefix);
      updatePickerSubmit(prefix);
      if (prefix === 'defect-create') renderCreateContext();
    } catch (error) { pickerReadFailed(prefix, 'attempt', error, function () { return loadAttempts(prefix, prefill); }, function () { return pickerValid(prefix, token, pickerToken); }); }
  }
  function selectedFailure(prefix) {
    const id = Number($('#' + prefix + '-attempt').val());
    const data = pickerData[prefix];
    return Number.isSafeInteger(id) && id > 0 && sameId(data.runId, $('#' + prefix + '-run').val()) && sameId(data.caseId, $('#' + prefix + '-case').val()) &&
      (data.attempts || []).some(function (attempt) { return sameId(attempt.id, id); }) ? id : null;
  }
  function assignee(selector, required) {
    const raw = $(selector).val().trim();
    if (!raw && !required) return null;
    const id = Number(raw);
    return Number.isSafeInteger(id) && id > 0 ? id : null;
  }
  function createLabels() {
    $('#defect-create-heading').text(createMode === 'link' ? t('defects.linkExisting', null, 'Link to existing defect') : t('shell.createDefect', null, 'Create defect'));
    $('#defect-create-submit').text(createMode === 'link' ? t('defects.linkExisting', null, 'Link to existing defect') : t('shell.createDefect', null, 'Create defect'));
    $('#defect-create-fields').toggleClass('d-none', createMode === 'link');
    $('#defect-create-fields input, #defect-create-fields select, #defect-create-fields textarea').prop('disabled', createMode === 'link');
    $('#defect-create-target-wrap').toggleClass('d-none', createMode !== 'link');
    $('#defect-create-target').prop('disabled', createMode !== 'link' || !linkableDefects.length);
  }
  async function loadLinkableDefects(token) {
    clearPickerFailures('defect-create', ['target']); linkableDefects = [];
    const target = $('#defect-create-target').empty().append(option('', t('defects.loadingDefects', null, 'Loading defects…'))).prop('disabled', true);
    try {
      const rows = await api.get(base('defects'));
      if (!valid(token) || createMode !== 'link') return;
      linkableDefects = rows.filter(function (item) { return item.status !== 'CLOSED'; });
      target.empty().append(option('', linkableDefects.length ? t('defects.selectDefect', null, 'Select a defect') : t('defects.noLinkableDefects', null, 'No open defects available for linking.'))).prop('disabled', !linkableDefects.length);
      linkableDefects.forEach(function (item) { target.append(option(item.id, 'BUG-' + String(item.keyNo).padStart(3, '0') + ' · ' + item.title + ' · ' + label(item.status))); });
      updatePickerSubmit('defect-create');
    } catch (error) { pickerReadFailed('defect-create', 'target', error, function () { return loadLinkableDefects(token); }, function () { return valid(token) && createMode === 'link'; }); }
  }
  function showCreate(prefill) {
    if (!active()) return;
    const token = ++generation; detail = null; actionMode = null; mutating = false; linkableDefects = [];
    createMode = prefill && prefill.action === 'link' ? 'link' : 'create';
    selectContext(Object.assign({}, prefill || {}, { action: createMode, defectId: undefined }));
    panels('create'); notice(''); formError('#defect-create-error', '');
    $('#defect-create-form')[0].reset();
    $('#defect-create-form input, #defect-create-form select, #defect-create-form textarea, #defect-create-form button').prop('disabled', false);
    $('#defect-create-context').empty();
    createLabels();
    loadRuns('defect-create', prefill);
    if (createMode === 'link') loadLinkableDefects(token);
    if (createMode === 'link') focusHeading('#defect-create-heading');
    else $('#defect-create-title').trigger('focus');
  }
  function showAction(mode) {
    if (!detail || mutating) return;
    pickerGeneration['defect-action']++;
    actionMode = mode;
    $('#defect-action-form')[0].reset(); formError('#defect-action-error', '');
    $('#defect-action-form input, #defect-action-form select, #defect-action-form textarea, #defect-action-submit').prop('disabled', false);
    $('#defect-action-form').removeClass('d-none');
    actionLabels(mode);
    $('#defect-action-assignee').val(mode === 'reopen' && detail.defect.assigneeId ? detail.defect.assigneeId : '');
    $('#defect-action-submit').prop('disabled', false);
    if (mode !== 'resolve') loadRuns('defect-action');
    if (mode === 'resolve') $('#defect-resolution').trigger('focus');
    else focusHeading('#defect-action-title');
  }
  function actionLabels(mode) {
    $('#defect-action-title').text({ resolve: t("defects.resolveDefect", null, 'Resolve defect'), reopen: t("defects.reopenWithANewFAIL", null, 'Reopen with a new FAIL'), add: t("defects.addFailureEvidence", null, 'Add failure evidence') }[mode]);
    $('#defect-action-help').text(mode === 'reopen' ? t('defects.reopenHelp', null, 'Choose a new, unlinked FAIL attempt and an active Developer/Admin assignee.')
      : mode === 'resolve' ? t('defects.resolveHelp', null, 'A resolution note is required. Closing later still requires a current PASS for every linked FAIL.')
        : t('defects.addEvidenceHelp', null, 'Only an existing FAIL attempt can be linked; removing a mistaken link never removes its history.'));
    $('#defect-resolution-wrap').toggleClass('d-none', mode !== 'resolve');
    $('#defect-action-assignee-wrap').toggleClass('d-none', mode !== 'reopen');
    $('#defect-action-evidence').toggleClass('d-none', mode === 'resolve');
  }
  function consumeRoute(context) {
    if (!active()) return;
    const value = context || {};
    if (value.projectId && !sameId(value.projectId, projectId)) return;
    routeContext = Object.assign({}, value);
    if (value.defectId) { launch = null; openDetail(value.defectId); }
    else if (value.action === 'create' || value.action === 'link') { launch = null; showCreate(value); }
    else if (launch && sameId(launch.projectId, projectId)) { const prefill = launch; launch = null; showCreate(prefill); }
    else list();
  }
  $(document).on('veriqra:failure-evidence', function (event) {
    const value = event.originalEvent.detail;
    if (projectId && String(value.projectId) === projectId) launch = value;
  });
  $(document).on('veriqra:project', function (event) {
    const next = event.originalEvent.detail.project;
    if (!next || (projectId && String(next.id) !== projectId)) launch = null;
    reset(); projectId = next ? String(next.id) : null;
    const context = navigation() && navigation().read();
    if (context) view = context.view;
    if (active()) consumeRoute(context);
  });
  $(document).on('veriqra:view', function (event) {
    const payload = event.originalEvent.detail, next = payload.view;
    if (next !== view) { reset(); view = next; }
    if (!active()) return;
    consumeRoute(payload.context || (navigation() && navigation().read()));
  });
  document.addEventListener('veriqra:localechange', function () {
    if (!active() || mutating) return;
    for (const prefix of ['defect-create', 'defect-action']) {
      const run = $('#' + prefix + '-run'), runCase = $('#' + prefix + '-case'), attempt = $('#' + prefix + '-attempt');
      const data = pickerData[prefix];
      if (run.find('option[value=""]').length) run.find('option[value=""]').text(t(!data.runs ? 'defects.loadingRuns' : data.runs.length ? 'defects.selectARun' : 'defects.noRuns'));
      if (runCase.find('option[value=""]').length) runCase.find('option[value=""]').text(t(!run.val() ? 'defects.selectARunFirst' : !data.cases ? 'defects.loadingRunCases' : data.cases.length ? 'defects.selectARunCase' : 'defects.noRunCases'));
      if (attempt.find('option[value=""]').length) attempt.find('option[value=""]').text(t(!runCase.val() ? 'defects.selectARunCaseFirst' : !sameId(data.caseId, runCase.val()) ? 'defects.loadingFailAttempts' : data.attempts.length ? 'defects.selectAFAILAttempt' : 'defects.noFailAttempts'));
      Object.keys(pickerFailures[prefix]).forEach(function (field) {
        $('#' + prefix + '-' + field + ' option[value=""]').text(t('common.unavailable', null, 'Unavailable'));
        $('#' + prefix + '-' + field + '-retry').text(t('common.retry', null, 'Retry'));
      });
    }
    for (const prefix of ['defect-create', 'defect-action']) {
      const selected = $('#' + prefix + '-attempt').val();
      (pickerData[prefix].attempts || []).forEach(function (item) { $('#' + prefix + '-attempt option[value="' + item.id + '"]').text('FAIL #' + item.attemptNo + ' · ' + date(item.executedAt) + ' · ID ' + item.id); });
      $('#' + prefix + '-attempt').val(selected);
    }
    if (!$('#defect-create-form').hasClass('d-none')) { createLabels(); renderCreateContext(false); return; }
    if (detail && !$('#defect-detail-panel').hasClass('d-none')) {
      renderDefect(detail.defect); renderActions(detail.defect); renderEvidence(detail);
      if (actionMode) actionLabels(actionMode);
    }
    else {
      const context = navigation() && navigation().read();
      if (!$('#defect-detail-panel').hasClass('d-none') && context && context.defectId && sameId(context.projectId, projectId)) openDetail(context.defectId);
      else list();
    }
  });
  $('#defect-new').on('click', function () { if (navigation()) navigation().open('defects', { projectId: projectId, action: 'create' }); else showCreate(null); });
  $('#defect-back, #defect-create-cancel').on('click', function () { if (navigation()) navigation().open('defects', { projectId: projectId }); else list(); });
  $('#defect-action-cancel').on('click', function () { actionMode = null; pickerGeneration['defect-action']++; clearPickerFailures('defect-action'); $('#defect-action-form').addClass('d-none'); focusHeading('#defect-detail-title'); });
  ['defect-create', 'defect-action'].forEach(function (prefix) {
    $('#' + prefix + '-run').on('change', function () { pickerError(prefix, ''); loadCases(prefix); if (prefix === 'defect-create') renderCreateContext(); });
    $('#' + prefix + '-case').on('change', function () { pickerError(prefix, ''); loadAttempts(prefix); if (prefix === 'defect-create') renderCreateContext(); });
    $('#' + prefix + '-attempt').on('change', function () { updatePickerSubmit(prefix); if (prefix === 'defect-create') renderCreateContext(); });
  });
  $('#defect-create-target').on('change', function () { updatePickerSubmit('defect-create'); });
  $('#defect-create-form').on('submit', async function (event) {
    event.preventDefault();
    if (!active() || mutating || $('#defect-create-submit').prop('disabled') || !this.reportValidity()) return;
    const failureAttemptId = selectedFailure('defect-create');
    if (!failureAttemptId) { formError('#defect-create-error', t("defects.selectAnExistingFAILAttempt", null, 'Select an existing FAIL attempt.')); return; }
    const target = createMode === 'link' && linkableDefects.find(function (item) { return sameId(item.id, $('#defect-create-target').val()); });
    if (createMode === 'link' && !target) { formError('#defect-create-error', t('defects.selectDefect', null, 'Select a defect')); return; }
    const rawAssignee = $('#defect-create-assignee').val().trim(), assigneeId = assignee('#defect-create-assignee', false);
    if (createMode !== 'link' && rawAssignee && !assigneeId) { formError('#defect-create-error', t('defects.invalidAssignee', null, 'Enter a valid assignee user ID.')); return; }
    const token = generation;
    const context = failureContext('defect-create');
    const sourceProjectId = projectId;
    const body = createMode === 'link' ? { failureAttemptId: failureAttemptId } : { failureAttemptId: failureAttemptId, title: $('#defect-create-title').val().trim(),
      description: $('#defect-create-description').val().trim() || null, severity: $('#defect-create-severity').val(),
      priority: $('#defect-create-priority').val(), assigneeId: assigneeId };
    mutating = true;
    $('#defect-create-form input, #defect-create-form select, #defect-create-form textarea, #defect-create-form button').prop('disabled', true);
    formError('#defect-create-error', '');
    try {
      const created = await api.post(target ? defectPath(target.id) + '/evidence' : base('defects'), body);
      const id = target ? target.id : created.id;
      announceLinked(id, context, sourceProjectId);
      if (valid(token)) {
        selectContext(Object.assign({}, routeContext, context, { defectId: String(id), action: undefined }));
        await openDetail(id, target ? t('defects.evidenceLinked', null, 'Failure evidence linked.') : null);
      }
    } catch (error) { if (valid(token)) formError('#defect-create-error', conflict(error)); }
    finally { if (valid(token)) {
      mutating = false;
      $('#defect-create-form input, #defect-create-form select, #defect-create-form textarea, #defect-create-form button').prop('disabled', false);
      createLabels(); updatePickerSubmit('defect-create');
    } }
  });
  $('#defect-action-form').on('submit', async function (event) {
    event.preventDefault();
    if (!active() || mutating || $('#defect-action-submit').prop('disabled') || !detail) return;
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
    const context = mode === 'add' || mode === 'reopen' ? failureContext('defect-action') : null;
    const sourceProjectId = projectId;
    mutationState(true);
    formError('#defect-action-error', '');
    try {
      await api.post(defectPath(defect.id) + (mode === 'add' ? '/evidence' : '/' + mode), body);
      if (context) announceLinked(defect.id, context, sourceProjectId);
      if (valid(token)) {
        await openDetail(defect.id, mode === 'resolve' ? t("defects.defectResolved", null, 'Defect resolved.') : null);
      }
    } catch (error) { if (valid(token)) formError('#defect-action-error', conflict(error, mode)); }
    finally { if (valid(token)) mutationState(false); }
  });
})(jQuery, window.VeriqraApi);
