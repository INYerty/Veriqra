(function ($, api) {
  'use strict';
  const t = window.I18n.t;
  // Project and view ownership stay in app.js. This module only renders current test assets.
  let projectId = null;
  let view = 'dashboard';
  let generation = 0;
  let rows = [];
  let detail = null;
  let detailId = null;
  let currentLinks = [];
  let editing = false;
  let projectArchived = false;
  let traceState = 'idle';
  let writing = false;
  let formContext = {};
  let pendingLink = null;
  let tracePickerGeneration = 0;
  const root = $('#asset-workspace');
  const active = function () { return view === 'requirements' || view === 'test-cases'; };
  const kind = function () { return view === 'requirements' ? 'requirements' : 'test-cases'; };
  const base = function (type) { return 'projects/' + encodeURIComponent(projectId) + '/' + type; };
  const valid = function (token) { return token === generation && projectId && active(); };
  const label = function (value) { return window.I18n.enumLabel(value); };
  const navigation = function () { return window.VeriqraQaNavigation; };
  const sameId = function (left, right) { return String(left) === String(right); };
  const writable = function () { return detail && !projectArchived && detail.row.status !== 'ARCHIVED'; };
  function routeContext() {
    const nav = navigation();
    const context = nav ? nav.read() : {};
    return !context.projectId || sameId(context.projectId, projectId) ? context : {};
  }
  function selectedContext(id) {
    const context = { projectId: projectId };
    context[view === 'requirements' ? 'requirementId' : 'testCaseId'] = String(id);
    const current = routeContext();
    ['sourceRequirementId', 'sourceTestCaseId', 'sourcePlanId', 'runId', 'runCaseId', 'attemptId'].forEach(function (name) {
      if (current[name]) context[name] = String(current[name]);
    });
    return context;
  }
  function originContext() {
    const context = { projectId: projectId };
    const current = routeContext();
    ['sourcePlanId', 'runId', 'runCaseId', 'attemptId'].forEach(function (name) {
      if (current[name]) context[name] = String(current[name]);
    });
    return context;
  }
  function selectContext(context) { const nav = navigation(); if (nav) nav.select(view, context); }
  function linkTo(nextView, context, text, primary) {
    const nav = navigation();
    const link = $('<a>').addClass('btn btn-' + (primary ? 'primary' : 'outline-secondary') + ' btn-sm')
      .attr('href', nav ? nav.href(nextView, context) : '#' + nextView).text(text);
    link.on('click', function (event) {
      if (event.ctrlKey || event.metaKey || event.shiftKey || event.altKey || (event.button != null && event.button !== 0)) return;
      if (nav) { event.preventDefault(); nav.open(nextView, context); }
      else if (nextView === view && (context.requirementId || context.testCaseId)) {
        event.preventDefault(); openDetail(context.requirementId || context.testCaseId);
      }
    });
    return link;
  }
  function updateControls() {
    $('#asset-edit').prop('disabled', !writable() || writing);
    $('#trace-add-button').prop('disabled', !writable() || writing || traceState !== 'loaded');
    $('#trace-submit').prop('disabled', !writable() || writing || traceState !== 'loaded' || !$('#trace-target').val());
    $('#asset-traces button, #asset-primary-actions a').attr('aria-disabled', writing ? 'true' : 'false');
    $('#asset-traces button, #asset-primary-actions button').prop('disabled', writing);
    $('#asset-partial-link-retry').prop('disabled', writing || projectArchived);
  }
  function conflict(failure) { return failure.status === 409 ? t('assets.conflict', null,
    'This item changed or cannot be edited in its current state. Reload its latest details before trying again.') : failure.message; }

  function notice(message, error) {
    $('#asset-notice').text(message || '').toggleClass('d-none', !message)
      .toggleClass('alert-danger', !!error).toggleClass('alert-success', !!message && !error);
  }
  function formError(message) { $('#asset-form-error').text(message || '').toggleClass('d-none', !message); }
  function focusHeading(selector) { $(selector).attr('tabindex', '-1').trigger('focus'); }
  function reset() {
    generation++;
    rows = [];
    detail = null;
    detailId = null;
    currentLinks = [];
    editing = false;
    traceState = 'idle'; writing = false; formContext = {}; pendingLink = null;
    root.find('#asset-list, #asset-detail, #asset-steps, #asset-traces, #step-editor').empty();
    $('#asset-save, #trace-add-button, #trace-submit').prop('disabled', false);
    root.find('#asset-list-panel, #asset-detail-panel, #asset-form, #trace-form').addClass('d-none');
    $('#asset-list-status').text('');
    $('#asset-primary-actions').empty();
    $('#asset-readonly-note, #asset-trace-load, #asset-trace-retry, #asset-partial-link-panel').addClass('d-none');
    $('#asset-trace-status, #asset-form-context, #asset-form-guidance').text('');
    notice(''); formError('');
  }
  function panels(which) {
    $('#asset-list-panel').toggleClass('d-none', which !== 'list');
    $('#asset-detail-panel').toggleClass('d-none', which !== 'detail');
    $('#asset-form').toggleClass('d-none', which !== 'form');
    $('#trace-form').addClass('d-none');
  }
  function badge(status) {
    const colors = { DRAFT: 'secondary', ACTIVE: 'success', READY: 'success', ARCHIVED: 'secondary', CONFIRMED: 'success', NEEDS_REVIEW: 'warning', REMOVED: 'secondary' };
    return $('<span class="badge text-bg-' + (colors[status] || 'secondary') + '">').attr('data-vq-state', status).text(label(status));
  }
  function field(term, value) {
    return $('<div class="asset-field">').append($('<dt>').text(term), $('<dd>').text(value == null || value === '' ? '—' : String(value)));
  }
  function statusField(status) {
    return $('<div class="asset-field">').append($('<dt>').text(t("common.status", null, 'Status')), $('<dd>').append(badge(status)));
  }
  function key(row) { return (view === 'requirements' ? 'REQ-' : 'TC-') + String(row.keyNo).padStart(3, '0'); }

  async function list() {
    if (!projectId || !active()) return;
    const token = ++generation;
    detail = null; detailId = null; writing = false;
    pendingLink = null;
    selectContext({ projectId: projectId });
    panels('list'); notice('');
    $('#asset-list-title').text(view === 'requirements' ? t("common.requirements", null, 'Requirements') : t("common.testCases", null, 'Test cases'));
    $('#asset-new').text(view === 'requirements' ? t("assets.createRequirement", null, 'Create requirement') : t("assets.createTestCase", null, 'Create test case'));
    $('#asset-list').empty();
    $('#asset-list-status').text(t("common.loading", null, 'Loading…'));
    focusHeading('#asset-list-title');
    try {
      const result = await api.get(base(kind()));
      if (!valid(token)) return;
      rows = result;
      $('#asset-list-status').text(result.length ? t('assets.itemCount', { count: window.I18n.formatNumber(result.length) }, '{count} item(s)')
        : t("assets.noItemsYetCreateTheFirstOne", null, 'No items yet. Create the first one.'));
      result.forEach(function (item) {
        const card = $('<div class="asset-list-row">');
        const summary = $('<div class="asset-list-summary">').append(
          $('<strong>').text(key(item) + ' · ' + item.title),
          $('<p class="mb-1 text-secondary">').text(item.description || t("common.noDescription", null, 'No description')));
        const meta = $('<div class="asset-list-meta">').append(badge(item.status),
          $('<span>').text(label(item.priority)), $('<small>').text('v' + item.version));
        const context = { projectId: projectId };
        context[view === 'requirements' ? 'requirementId' : 'testCaseId'] = String(item.id);
        const open = linkTo(view, context, t('assets.viewItem', { key: key(item) }, 'View {key}'));
        card.append(summary, meta, open);
        $('#asset-list').append(card);
      });
    } catch (failure) { if (valid(token)) {
      rows = [];
      $('#asset-list-status').text(t('common.unavailable', null, 'Unavailable')); notice(failure.message, true);
      $('#asset-list').append($('<button type="button" class="btn btn-outline-secondary btn-sm">')
        .text(t('common.retry', null, 'Retry')).on('click', function () { if (valid(token)) return list(); }));
    } }
  }

  async function linksForCase(caseId, token) {
    // The approved API has only requirement-side traceability reads.
    const requirements = await api.get(base('requirements'));
    if (!valid(token)) return [];
    const groups = await Promise.all(requirements.map(function (req) {
      return Promise.resolve(api.get(base('requirements') + '/' + req.id + '/test-cases'))
        .then(function (links) { return links.filter(function (link) { return sameId(link.testCase.id, caseId); }).map(function (link) { return { requirement: req, link: link }; }); });
    }));
    if (!valid(token)) return [];
    return groups.flat();
  }

  async function openDetail(id, message) {
    const token = ++generation;
    detail = null;
    detailId = String(id);
    currentLinks = [];
    traceState = 'idle'; writing = false;
    selectContext(selectedContext(id));
    editing = false;
    panels('detail'); notice('');
    $('#asset-detail-title').text(t("common.loading", null, 'Loading…'));
    focusHeading('#asset-detail-title');
    $('#asset-detail, #asset-steps, #asset-traces').empty();
    $('#asset-primary-actions').empty();
    $('#asset-readonly-note, #asset-trace-load, #asset-trace-retry, #asset-partial-link-panel').addClass('d-none');
    $('#asset-trace-status').text(''); updateControls();
    if (pendingLink && sameId(pendingLink.caseId, id)) showPendingLink();
    try {
      const current = await api.get(base(kind()) + '/' + encodeURIComponent(id));
      if (!valid(token)) return;
      const row = view === 'requirements' ? current : current.testCase;
      detail = { row: row, steps: view === 'test-cases' ? current.steps : [] };
      $('#asset-detail-title').text(key(row) + ' · ' + row.title);
      $('#asset-detail').append($('<dl class="asset-fields">').append(
        statusField(row.status), field(t("common.priority", null, 'Priority'), label(row.priority)),
        field(t("common.description", null, 'Description'), row.description), field(t("common.version", null, 'Version'), row.version), field(t("common.updated", null, 'Updated'), window.I18n.formatDateTime(row.updatedAt))));
      $('#asset-steps-section').toggleClass('d-none', view !== 'test-cases');
      if (view === 'test-cases') {
        $('#asset-detail dl').append(field(t("assets.preconditions", null, 'Preconditions'), row.preconditions));
        renderSteps(current.steps);
      }
      $('#asset-readonly-note').text(t(projectArchived ? 'assets.projectReadOnly' : 'assets.readOnly'))
        .toggleClass('d-none', !projectArchived && row.status !== 'ARCHIVED');
      renderPrimaryActions(); updateControls();
      $('#trace-help').text(view === 'requirements' ? t("assets.linkedTestCasesIncludingRemovedHistory", null, 'Linked test cases, including removed history.') : t("assets.linkedRequirementsIncludingRemovedHistory", null, 'Linked requirements, including removed history.'));
      $('#trace-help').append(document.createTextNode(' ' + t('assets.traceMeaning', null, 'Link status describes coverage review, not an execution result.')));
      if (message) notice(message);
      if (pendingLink && sameId(pendingLink.caseId, row.id)) showPendingLink();
      if (view === 'requirements') await loadTraceability();
      else {
        $('#asset-trace-status').text(t('assets.traceNotLoaded', null, 'Load linked requirements before changing links.'));
        $('#asset-trace-load').removeClass('d-none');
      }
      return valid(token) && detail && sameId(detail.row.id, id);
    } catch (failure) { if (valid(token)) {
      $('#asset-detail-title').text(t('common.unavailable', null, 'Unavailable')); notice(failure.message, true); updateControls();
      $('#asset-primary-actions').append($('<button type="button" class="btn btn-outline-secondary btn-sm">')
        .text(t('common.retry', null, 'Retry')).on('click', function () {
          if (valid(token) && !writing && sameId(detailId, id)) return openDetail(id, message);
        }));
    } }
  }
  function renderPrimaryActions() {
    const current = routeContext();
    const target = $('#asset-primary-actions').empty();
    if (current.sourceRequirementId && view === 'test-cases') target.append(linkTo('requirements',
      Object.assign(originContext(), { requirementId: current.sourceRequirementId, sourceTestCaseId: String(detail.row.id) }), t('assets.backToRequirement')));
    if (current.sourceTestCaseId && view === 'requirements') target.append(linkTo('test-cases',
      Object.assign(originContext(), { testCaseId: current.sourceTestCaseId }), t('assets.backToTestCase')));
    if (current.sourcePlanId) target.append(linkTo('test-plans', { projectId: projectId, planId: current.sourcePlanId }, t('assets.backToPlan')));
    if (current.runId && current.runCaseId) target.append(linkTo('runs',
      { projectId: projectId, runId: current.runId, runCaseId: current.runCaseId, attemptId: current.attemptId }, t('shell.backToRun')));
    if (!writable()) return;
    target.append(view === 'requirements' ? linkTo('test-cases',
      { projectId: projectId, action: 'create', sourceRequirementId: String(detail.row.id) }, t('assets.createLinkedTestCase'), true)
      : linkTo('test-plans', { projectId: projectId, action: 'create', sourceTestCaseId: String(detail.row.id) }, t('assets.createPlanWithCase'), true));
  }
  async function loadTraceability() {
    if (!detail || traceState === 'loading' || writing) return;
    const id = detail.row.id;
    const token = generation;
    traceState = 'loading'; currentLinks = [];
    $('#asset-traces').empty(); $('#trace-form').addClass('d-none');
    $('#asset-trace-load, #asset-trace-retry').addClass('d-none');
    $('#asset-trace-status').text(t('assets.loadingTraceability', null, 'Loading traceability…')); updateControls();
    try {
      const links = view === 'requirements' ? await api.get(base('requirements') + '/' + id + '/test-cases') : await linksForCase(id, token);
      if (!valid(token) || !detail || !sameId(detail.row.id, id)) return;
      traceState = 'loaded'; $('#asset-trace-status').text(''); renderLinks(links); updateControls();
    } catch (failure) { if (valid(token) && detail && sameId(detail.row.id, id)) {
      traceState = 'error';
      $('#asset-trace-status').text(t('assets.traceLoadFailed', null, 'Traceability could not be loaded. Retry before changing links.') + ' ' + failure.message);
      $('#asset-trace-retry').removeClass('d-none'); updateControls();
    } }
  }
  function renderSteps(steps) {
    const target = $('#asset-steps').empty();
    if (!steps.length) { target.append($('<p class="text-secondary">').text(t("assets.noStepsYet", null, 'No steps yet.'))); return; }
    steps.forEach(function (step) {
      target.append($('<div class="asset-step">').append(
        $('<strong>').text(t('assets.stepNumber', { number: step.stepOrder }, 'Step {number}')),
        $('<div>').append($('<span class="text-secondary">').text(t("assets.action", null, 'Action: ')), document.createTextNode(step.action)),
        $('<div>').append($('<span class="text-secondary">').text(t("assets.expected", null, 'Expected: ')), document.createTextNode(step.expectedResult))));
    });
  }
  function renderLinks(links) {
    currentLinks = links;
    const target = $('#asset-traces').empty();
    if (!links.length) { target.append($('<p class="text-secondary">').text(t("assets.noTraceabilityRecordsYet", null, 'No traceability records yet.'))); return; }
    links.forEach(function (entry) {
      const row = view === 'requirements' ? entry.testCase : entry.requirement;
      const status = view === 'requirements' ? entry.status : entry.link.status;
      const requirementId = view === 'requirements' ? detail.row.id : row.id;
      const caseId = view === 'requirements' ? row.id : detail.row.id;
      const item = $('<div class="asset-link-row">').append(
        $('<span>').text((view === 'requirements' ? 'TC-' : 'REQ-') + String(row.keyNo).padStart(3, '0') + ' · ' + row.title), badge(status), badge(row.status));
      item.append(view === 'requirements' ? linkTo('test-cases', Object.assign(originContext(), { testCaseId: String(caseId), sourceRequirementId: String(requirementId) }), t('assets.openTestCase'))
        : linkTo('requirements', Object.assign(originContext(), { requirementId: String(requirementId), sourceTestCaseId: String(caseId) }), t('assets.openRequirement')));
      if (writable() && row.status !== 'ARCHIVED') {
        if (status === 'NEEDS_REVIEW') item.append(actionButton(t("common.confirm", null, 'Confirm'), 'confirm', requirementId, caseId, row.title));
        if (status !== 'REMOVED') item.append(actionButton(t("assets.markRemoved", null, 'Mark removed'), 'remove', requirementId, caseId, row.title));
        if (status === 'REMOVED') item.append(actionButton(t("assets.reattach", null, 'Reattach'), '', requirementId, caseId, row.title));
      }
      target.append(item);
    });
  }
  function actionButton(text, action, reqId, caseId, name) {
    return $('<button type="button" class="btn btn-outline-secondary btn-sm">').text(text).on('click', async function () {
      if (!writable() || writing || traceState !== 'loaded') return;
      if (action === 'remove' && !window.confirm(t('assets.removeLinkConfirm', { name: name },
        'Mark the link to "{name}" as REMOVED? The history will remain.'))) return;
      const token = generation;
      const id = detail.row.id;
      writing = true; updateControls();
      try {
        const path = base('requirements') + '/' + reqId + '/test-cases/' + caseId + (action ? '/' + action : '');
        await api.post(path, {});
        if (valid(token)) { const opened = await openDetail(id); if (opened && view === 'test-cases') await loadTraceability(); }
      } catch (failure) { if (valid(token)) notice(conflict(failure), true); }
      finally { if (valid(token)) { writing = false; updateControls(); } }
    });
  }

  function stepRow(step) {
    const row = $('<div class="step-editor-row">');
    const actionId = 'step-action-' + Math.random().toString(36).slice(2);
    const expectedId = 'step-expected-' + Math.random().toString(36).slice(2);
    row.append($('<strong class="step-number">').text(t("assets.step", null, 'Step')),
      $('<label class="form-label">').attr('for', actionId).text(t("assets.actionMessage", null, 'Action *')),
      $('<textarea class="form-control step-action" rows="2" required>').attr('id', actionId).val(step.action || ''),
      $('<label class="form-label mt-2">').attr('for', expectedId).text(t("assets.expectedResult", null, 'Expected result *')),
      $('<textarea class="form-control step-expected" rows="2" required>').attr('id', expectedId).val(step.expectedResult || ''));
    const controls = $('<div class="step-actions">');
    [[t("assets.moveUp", null, 'Move up'), -1], [t("assets.moveDown", null, 'Move down'), 1]].forEach(function (entry) {
      controls.append($('<button type="button" class="btn btn-outline-secondary btn-sm">').text(entry[0]).on('click', function () {
        const sibling = entry[1] < 0 ? row.prev('.step-editor-row') : row.next('.step-editor-row');
        if (sibling.length) { if (entry[1] < 0) row.insertBefore(sibling); else row.insertAfter(sibling); renumberSteps(); }
      }));
    });
    controls.append($('<button type="button" class="btn btn-outline-danger btn-sm">').text(t("assets.removeStep", null, 'Remove step')).on('click', function () {
      if (window.confirm(t("assets.removeThisStepFromTheTestCaseDefinition", null, 'Remove this step from the test case definition?'))) { row.remove(); renumberSteps(); }
    }));
    row.append(controls);
    $('#step-editor').append(row);
    renumberSteps();
  }
  function renumberSteps() {
    $('#step-editor .step-number').each(function (index) { $(this).text(t('assets.stepNumber', { number: index + 1 }, 'Step {number}')); });
  }
  function showForm(update, context) {
    if (!projectId || !active() || projectArchived || writing || (update && !writable())) return;
    generation++;
    editing = update;
    formContext = update ? {} : (context || {});
    $('#asset-save').prop('disabled', false);
    formError(''); notice('');
    const row = update ? detail.row : null;
    $('#asset-form')[0].reset();
    $('#asset-form-title').text(t(update ? (view === 'requirements' ? 'assets.editRequirement' : 'assets.editTestCase')
      : (view === 'requirements' ? 'assets.createRequirement' : 'assets.createTestCase')));
    $('#asset-form-context').text(formContext.sourceRequirementId ? t('assets.requirementContext',
      { id: formContext.sourceRequirementId }, 'After saving, this case will be linked to requirement #{id}. Saving and linking are separate operations.') : '');
    $('#asset-form-guidance').text(t(view === 'test-cases' ? 'assets.definitionChangeHint' : 'assets.requirementChangeHint'));
    $('#asset-title').val(row ? row.title : '');
    $('#asset-description').val(row ? row.description || '' : '');
    $('#asset-priority').val(row ? row.priority : 'MEDIUM');
    $('#asset-status-wrap').toggleClass('d-none', !update);
    $('#asset-status').empty();
    (view === 'requirements' ? ['DRAFT', 'ACTIVE', 'ARCHIVED'] : ['DRAFT', 'READY', 'ARCHIVED']).forEach(function (status) {
      $('#asset-status').append($('<option>').val(status).text(label(status)));
    });
    if (row) $('#asset-status').val(row.status);
    $('#asset-case-fields').toggleClass('d-none', view !== 'test-cases');
    $('#asset-preconditions').val(row ? row.preconditions || '' : '');
    $('#step-editor').empty();
    if (view === 'test-cases' && update) detail.steps.forEach(stepRow);
    panels('form');
    $('#asset-title').trigger('focus');
  }

  function showPendingLink() {
    $('#asset-partial-link-status').text(t('assets.caseCreatedLinkPendingWithId',
      { id: pendingLink.caseId, requirementId: pendingLink.requirementId },
      'Test case #{id} was created, but its link to requirement #{requirementId} could not be saved. Retry linking this saved case.')
      + (pendingLink.failureMessage ? ' ' + pendingLink.failureMessage : ''));
    $('#asset-partial-link-panel').removeClass('d-none');
    $('#asset-partial-link-retry').prop('disabled', writing || projectArchived);
  }
  async function attachCreatedCase(token, link) {
    // Case creation and traceability are separate existing API writes. Never repeat creation on link failure.
    try {
      await api.post(base('requirements') + '/' + link.requirementId + '/test-cases/' + link.caseId, {});
      if (!valid(token)) return;
      pendingLink = null;
      await openDetail(link.caseId, t('assets.caseLinked', null, 'Test case created and linked. Review the link to confirm coverage.'));
    } catch (failure) {
      if (!valid(token)) return;
      if (failure.status === 409) {
        // An earlier response may have been lost after attach succeeded. Verify that specific relationship only.
        try {
          const existing = await api.get(base('requirements') + '/' + link.requirementId + '/test-cases');
          if (!valid(token)) return;
          if (existing.some(function (entry) { return sameId(entry.testCase.id, link.caseId) && entry.status !== 'REMOVED'; })) {
            pendingLink = null;
            await openDetail(link.caseId, t('assets.caseLinked', null, 'Test case created and linked. Review the link to confirm coverage.'));
            return;
          }
        } catch (_) { if (!valid(token)) return; }
      }
      pendingLink = Object.assign({}, link, { failureMessage: conflict(failure) });
      await openDetail(link.caseId);
    }
  }
  function applyRoute(context) {
    const current = context || routeContext();
    if (current.projectId && !sameId(current.projectId, projectId)) return;
    const id = view === 'requirements' ? current.requirementId : current.testCaseId;
    if (current.action === 'create' && projectArchived) {
      list(); notice(t('assets.projectReadOnly', null, 'This project is archived and read-only.'), true);
    } else if (current.action === 'create') showForm(false, current);
    else if (id) openDetail(id);
    else list();
  }

  $(document).on('veriqra:project', function (event) {
    reset();
    const project = event.originalEvent.detail.project;
    projectId = project ? String(project.id) : null;
    projectArchived = !!project && project.status === 'ARCHIVED';
    const current = routeContext();
    if (current.view) view = current.view;
    $('#asset-new').prop('disabled', projectArchived);
    if (projectId && active()) applyRoute();
  });
  $(document).on('veriqra:view', function (event) {
    const next = event.originalEvent.detail.view;
    reset(); view = next;
    if (projectId && active()) applyRoute(event.originalEvent.detail.context);
  });
  document.addEventListener('veriqra:localechange', function () {
    if (!projectId || !active()) return;
    if (!$('#asset-form').hasClass('d-none')) {
      $('#asset-form-title').text(t(editing ? (view === 'requirements' ? 'assets.editRequirement' : 'assets.editTestCase')
        : (view === 'requirements' ? 'assets.createRequirement' : 'assets.createTestCase')));
      $('#asset-status option').each(function () { $(this).text(label(this.value)); });
      $('#asset-form-context').text(formContext.sourceRequirementId ? t('assets.requirementContext', { id: formContext.sourceRequirementId }) : '');
      $('#asset-form-guidance').text(t(view === 'test-cases' ? 'assets.definitionChangeHint' : 'assets.requirementChangeHint'));
      $('#step-editor .step-editor-row').each(function () {
        $(this).find('.step-action').prev('label').text(t('assets.actionMessage'));
        $(this).find('.step-expected').prev('label').text(t('assets.expectedResult'));
        $(this).find('.step-actions button').eq(0).text(t('assets.moveUp'));
        $(this).find('.step-actions button').eq(1).text(t('assets.moveDown'));
        $(this).find('.step-actions button').eq(2).text(t('assets.removeStep'));
      });
      renumberSteps();
    } else if (!$('#asset-detail-panel').hasClass('d-none')) {
      const current = routeContext();
      const id = current[view === 'requirements' ? 'requirementId' : 'testCaseId'] || (!navigation() && detailId);
      if (!id) return list();
      const loaded = detail && sameId(detail.row.id, id) && traceState === 'loaded';
      const loading = openDetail(id);
      const token = generation;
      loading.then(function (opened) { if (opened && valid(token) && loaded && view === 'test-cases') loadTraceability(); });
    }
    else list();
  });
  $('#asset-back').on('click', list);
  $('#asset-new').on('click', function () {
    if (projectArchived) return;
    selectContext({ projectId: projectId, action: 'create' }); showForm(false);
  });
  $('#asset-edit').on('click', function () { if (detail) showForm(true); });
  $('#asset-form-cancel').on('click', function () { if (editing && detail) openDetail(detail.row.id); else list(); });
  $('#step-add').on('click', function () { stepRow({}); });
  $('#asset-trace-load, #asset-trace-retry').on('click', loadTraceability);
  $('#asset-partial-link-retry').on('click', async function () {
    if (!pendingLink || writing || projectArchived || view !== 'test-cases' || !sameId(pendingLink.caseId, detailId)) return;
    const token = generation;
    writing = true; updateControls();
    await attachCreatedCase(token, pendingLink);
    if (valid(token)) { writing = false; updateControls(); }
  });
  $('#asset-form').on('submit', async function (event) {
    event.preventDefault();
    if (writing || projectArchived || $('#asset-save').prop('disabled') || !this.reportValidity()) return;
    const token = generation;
    const button = $('#asset-save').prop('disabled', true);
    formError('');
    const body = { title: $('#asset-title').val().trim(), description: $('#asset-description').val().trim() || null,
      priority: $('#asset-priority').val() };
    if (view === 'test-cases') {
      body.preconditions = $('#asset-preconditions').val().trim() || null;
      body.steps = $('#step-editor .step-editor-row').map(function (index) {
        return { stepOrder: index + 1, action: $(this).find('.step-action').val().trim(),
          expectedResult: $(this).find('.step-expected').val().trim() };
      }).get();
      if (body.steps.some(function (step) { return !step.action || !step.expectedResult; })) {
        formError(t("assets.everyStepNeedsAnActionAndExpectedResult", null, 'Every step needs an action and expected result.')); button.prop('disabled', false); return;
      }
    }
    if (!body.title) { formError(t("assets.titleIsRequired", null, 'Title is required.')); button.prop('disabled', false); return; }
    if (editing) {
      body.status = $('#asset-status').val();
      body.expectedVersion = detail.row.version;
      if (view === 'test-cases' && body.status === 'READY' && !body.steps.length) {
        formError(t('assets.readyNeedsStep', null, 'Add at least one complete step before marking the case Ready.'));
        button.prop('disabled', false); return;
      }
      if (body.status === 'ARCHIVED' && detail.row.status !== 'ARCHIVED' && !window.confirm(t('assets.archiveConfirm',
        { title: detail.row.title }, 'Archive "{title}"?'))) {
        button.prop('disabled', false); return;
      }
    }
    const update = editing;
    const previousStatus = update ? detail.row.status : null;
    const linkRequirementId = !update && view === 'test-cases' ? formContext.sourceRequirementId : null;
    writing = true;
    try {
      const saved = update
        ? await api.put(base(kind()) + '/' + detail.row.id, body)
        : await api.post(base(kind()), body);
      if (!valid(token)) return;
      if (linkRequirementId) await attachCreatedCase(token, { requirementId: String(linkRequirementId), caseId: String(saved.id) });
      else await openDetail(saved.id, update && view === 'test-cases' && saved.status === 'DRAFT' && (body.status === 'READY' || previousStatus === 'READY')
        ? t('assets.savedDraft', null, 'Saved as Draft because the definition changed. Review affected links before marking Ready.') : '');
    } catch (failure) {
      if (valid(token)) formError(conflict(failure));
    } finally { if (token === generation) { writing = false; button.prop('disabled', false); updateControls(); } }
  });
  async function loadTraceTargets() {
    if (!writable() || writing || traceState !== 'loaded') return;
    const token = generation;
    const selection = ++tracePickerGeneration;
    const current = function () { return valid(token) && selection === tracePickerGeneration; };
    $('#trace-add-button, #trace-submit').prop('disabled', true); notice('');
    const target = $('#trace-target').empty().prop('disabled', true)
      .append($('<option>').val('').text(t('common.loading', null, 'Loading…')));
    $('#trace-form').removeClass('d-none');
    focusHeading('#trace-target-label');
    try {
      const type = view === 'requirements' ? 'test-cases' : 'requirements';
      const options = await api.get(base(type));
      if (!current()) return;
      const linkedIds = currentLinks.map(function (link) {
        return String(view === 'requirements' ? link.testCase.id : link.requirement.id);
      });
      const eligible = options.filter(function (row) { return row.status !== 'ARCHIVED' && !linkedIds.includes(String(row.id)); });
      target.empty().prop('disabled', !eligible.length);
      eligible.forEach(function (row) {
        target.append($('<option>').val(row.id).text((type === 'test-cases' ? 'TC-' : 'REQ-') +
          String(row.keyNo).padStart(3, '0') + ' · ' + row.title));
      });
      if (!target.children().length) target.append($('<option>').val('').text(t(!options.length
        ? (view === 'requirements' ? 'assets.noCasesForLink' : 'assets.noRequirementsForLink') : 'assets.noEligibleLinks')));
      $('#trace-target-label').text(view === 'requirements' ? t("assets.testCase", null, 'Test case') : t("assets.requirement", null, 'Requirement'));
      $('#trace-submit').prop('disabled', !eligible.length);
      $('#trace-form').removeClass('d-none');
      target.trigger('focus');
    } catch (failure) { if (current()) {
      target.empty().append($('<option>').val('').text(t('common.unavailable', null, 'Unavailable'))).prop('disabled', true);
      notice(failure.message, true);
      $('#asset-notice').append($('<button type="button" class="btn btn-outline-secondary btn-sm ms-2">')
        .text(t('common.retry', null, 'Retry')).on('click', function () { if (current()) return loadTraceTargets(); }));
    } }
    finally { if (current()) updateControls(); }
  }
  $('#trace-add-button').on('click', loadTraceTargets);
  $('#trace-cancel').on('click', function () { ++tracePickerGeneration; $('#trace-form').addClass('d-none'); notice(''); updateControls(); $('#trace-add-button').trigger('focus'); });
  $('#trace-form').on('submit', async function (event) {
    event.preventDefault();
    if (!writable() || writing || traceState !== 'loaded' || !$('#trace-target').val()) return;
    const token = generation;
    const button = $('#trace-submit').prop('disabled', true);
    const reqId = view === 'requirements' ? detail.row.id : $('#trace-target').val();
    const caseId = view === 'requirements' ? $('#trace-target').val() : detail.row.id;
    const id = detail.row.id;
    writing = true; updateControls();
    try {
      await api.post(base('requirements') + '/' + reqId + '/test-cases/' + caseId, {});
      if (valid(token)) { const opened = await openDetail(id); if (opened && view === 'test-cases') await loadTraceability(); }
    } catch (failure) { if (valid(token)) notice(conflict(failure), true); }
    finally { if (token === generation) { writing = false; button.prop('disabled', false); updateControls(); } }
  });
})(jQuery, window.VeriqraApi);
